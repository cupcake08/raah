#include "ui_map.h"

#include <math.h>
#include <string.h>

#include "ui_theme.h"

#ifdef UI_MAP_STATS
ui_map_stats_t ui_map_stats;
ui_map_knobs_t ui_map_knobs;
#define STAT(field, n) (ui_map_stats.field += (n))
#define KNOB(name)     (ui_map_knobs.name)
#else
#define STAT(field, n) ((void)0)
#define KNOB(name)     false
#endif

typedef struct {
    float cx, ry;   /* rider on screen */
    float c, s;     /* cos and sin of the heading, already scaled to pixels per metre */
    float vx, vy;   /* rider in the map frame */
} xform_t;

typedef struct {
    map_data_t data;
    map_view_t view;
    lv_color_t route_ink;
    /* Rider projected onto the route: segment index and the point itself. */
    int32_t route_seg;
    float proj_x, proj_y;
    /* Per-frame state, rebuilt by cull() when the view, data or widget position changes. */
    bool stale;
    lv_area_t culled_for;
    xform_t t;
    uint16_t nvis;
    int32_t route_lo, route_hi;     /* route points spanning every visible route segment */
    lv_obj_t *cover;                /* opaque rounded box over the map, left alone by map changes */
    int32_t cover_r;
} map_t;

/*
 * Roads that can touch the screen this frame, in draw order (minor classes first), with the box each
 * one's stroke can reach. LVGL renders the screen as strips of 20 rows and calls map_draw() once per
 * strip, so this list lets each strip skip roads that miss it without transforming their points.
 * One map widget exists at a time; `vis_owner` guards the shared list anyway.
 */
typedef struct {
    uint16_t line;
    int16_t x1, x2, y1, y2;     /* screen box, grown by the stroke */
} vis_t;
static vis_t vis[UI_MAP_MAX_LINES];
static const map_t *vis_owner;

static const struct {
    uint32_t color;
    uint8_t width;
} road_style[MAP_CLASS_COUNT] = {
    [MAP_CLASS_SERVICE] = {0x1A1E24, 3},
    [MAP_CLASS_MINOR] = {0x2B3139, 6},
    [MAP_CLASS_MID] = {0x3B424D, 9},
    [MAP_CLASS_MAJOR] = {0x535C69, 12},
};

#define ROUTE_W      10
#define ROUTE_CASE_W 18

/*
 * Round to the nearest pixel. GCC lowers lroundf()/lrintf() to a libcall on Xtensa: about 30 integer
 * instructions plus a windowed call that makes the caller spill and reload its FP registers. The FPU's
 * round.s does it in one instruction.
 */
#if defined(__XTENSA__)
static inline int32_t px_round(float v)
{
    int32_t r;
    __asm__("round.s %0, %1, 0" : "=a"(r) : "f"(v));
    return r;
}
#else
static inline int32_t px_round(float v)
{
    return (int32_t)lrintf(v);
}
#endif

static inline lv_point_precise_t xf(const xform_t *t, float x, float y)
{
    float dx = x - t->vx, dy = y - t->vy;
    return (lv_point_precise_t){px_round(t->cx + dx * t->c - dy * t->s), px_round(t->ry - dx * t->s - dy * t->c)};
}

static int16_t clamp16(int32_t v)
{
    return (int16_t)LV_CLAMP(INT16_MIN, v, INT16_MAX);
}

static void stroke(lv_layer_t *layer, const lv_point_precise_t *p, int32_t n, int32_t w, lv_color_t c)
{
    lv_draw_line_dsc_t d;
    lv_draw_line_dsc_init(&d);
    d.points = (lv_point_precise_t *)p;
    d.point_cnt = n;
    d.width = w;
    d.color = c;
    d.round_start = !KNOB(no_caps);
    d.round_end = !KNOB(no_caps);
    lv_draw_line(layer, &d);
    STAT(polylines, 1);
    STAT(segments, n - 1);
}

/*
 * Draws only the runs of segments that can touch the strip being rendered. LVGL copies the whole point
 * array into a heap block and rasterises it segment by segment, so a road crossing the screen would
 * otherwise cost every segment in every strip. A run ends on a point whose cap is drawn either way, so
 * the pixels are the same as drawing the whole polyline.
 */
static void polyline(lv_layer_t *layer, const lv_point_precise_t *p, int32_t n, int32_t w, lv_color_t c)
{
    const lv_area_t *clip = &layer->_clip_area;
    int32_t x1 = clip->x1 - w, x2 = clip->x2 + w, y1 = clip->y1 - w, y2 = clip->y2 + w;
    int32_t start = -1;
    for (int32_t i = 0; i + 1 < n; i++) {
        const lv_point_precise_t *a = &p[i], *b = &p[i + 1];
        bool hit = LV_MAX(a->y, b->y) >= y1 && LV_MIN(a->y, b->y) <= y2 && LV_MAX(a->x, b->x) >= x1 &&
                   LV_MIN(a->x, b->x) <= x2;
        if (hit && start < 0) start = i;
        if (!hit && start >= 0) {
            stroke(layer, p + start, i - start + 1, w, c);
            start = -1;
        }
    }
    if (start >= 0) stroke(layer, p + start, n - start, w, c);
}

static void chevron(lv_layer_t *layer, float x, float y, float s, lv_color_t c)
{
    lv_draw_triangle_dsc_t d;
    lv_draw_triangle_dsc_init(&d);
    d.color = c;
    d.opa = LV_OPA_COVER;
    lv_point_precise_t tip = {x, y - 15 * s}, notch = {x, y + 5 * s};
    d.p[0] = tip;
    d.p[1] = (lv_point_precise_t){x + 12 * s, y + 13 * s};
    d.p[2] = notch;
    lv_draw_triangle(layer, &d);
    d.p[1] = notch;
    d.p[2] = (lv_point_precise_t){x - 12 * s, y + 13 * s};
    lv_draw_triangle(layer, &d);
}

/* Screen bounding box of n map points, grown by `pad`; returns false when it misses `a`. */
static bool on_screen(const xform_t *t, const map_pt_t *p, int32_t n, const lv_area_t *a, int32_t pad,
                      lv_area_t *box)
{
    int32_t bx1 = INT32_MAX, by1 = INT32_MAX, bx2 = INT32_MIN, by2 = INT32_MIN;
    for (int32_t k = 0; k < n; k++) {
        lv_point_precise_t q = xf(t, p[k].x, p[k].y);
        bx1 = LV_MIN(bx1, q.x);
        bx2 = LV_MAX(bx2, q.x);
        by1 = LV_MIN(by1, q.y);
        by2 = LV_MAX(by2, q.y);
    }
    STAT(transforms, n);
    *box = (lv_area_t){bx1 - pad, by1 - pad, bx2 + pad, by2 + pad};
    return box->x2 >= a->x1 && box->x1 <= a->x2 && box->y2 >= a->y1 && box->y1 <= a->y2;
}

/* Once per frame: the view transform, the visible roads in draw order and the visible stretch of route. */
static void cull(map_t *m, const lv_area_t *a)
{
    float h = m->view.heading * (float)M_PI / 180.f, inv = 1.f / m->view.m_per_px;
    m->t = (xform_t){
        .cx = a->x1 + lv_area_get_width(a) / 2.f,
        .ry = a->y1 + (float)m->view.rider_y,
        .c = cosf(h) * inv,
        .s = sinf(h) * inv,
        .vx = m->view.x,
        .vy = m->view.y,
    };

    /* Roads, minor classes first so major roads sit on top. */
    m->nvis = 0;
    for (int cls = 0; cls < MAP_CLASS_COUNT; cls++) {
        for (uint16_t i = 0; i < m->data.nlines; i++) {
            const map_line_t *l = &m->data.lines[i];
            lv_area_t b;
            if (l->cls != cls || !on_screen(&m->t, &m->data.pts[l->first], l->n, a, road_style[cls].width, &b))
                continue;
            vis[m->nvis++] = (vis_t){i, clamp16(b.x1), clamp16(b.x2), clamp16(b.y1), clamp16(b.y2)};
        }
    }

    m->route_lo = 1;
    m->route_hi = 0;
    for (int32_t i = 0; i + 1 < m->data.nroute; i++) {
        lv_area_t b;
        if (!on_screen(&m->t, &m->data.route[i], 2, a, ROUTE_CASE_W, &b)) continue;
        if (m->route_lo > m->route_hi) m->route_lo = i;
        m->route_hi = i + 1;
    }

    vis_owner = m;
    m->culled_for = *a;
    m->stale = false;
}

static void map_draw(lv_event_t *e)
{
    lv_obj_t *obj = lv_event_get_target_obj(e);
    map_t *m = lv_obj_get_user_data(obj);
    lv_layer_t *layer = lv_event_get_layer(e);
    if (!m->data.pts || m->view.m_per_px <= 0) return;
    STAT(draws, 1);

    lv_area_t a;
    lv_obj_get_coords(obj, &a);
    if (m->stale || vis_owner != m || memcmp(&a, &m->culled_for, sizeof(a))) cull(m, &a);
    const xform_t *t = &m->t;
    const lv_area_t *clip = &layer->_clip_area;

    static lv_point_precise_t buf[256];

    lv_color_t ink[MAP_CLASS_COUNT];
    for (int cls = 0; cls < MAP_CLASS_COUNT; cls++) ink[cls] = lv_color_hex(road_style[cls].color);
    for (uint16_t v = 0; v < (KNOB(no_roads) ? 0 : m->nvis); v++) {
        /* Strips and the slivers beside the turn card each draw only the roads that cross them. */
        if (vis[v].y2 < clip->y1 || vis[v].y1 > clip->y2 || vis[v].x2 < clip->x1 || vis[v].x1 > clip->x2) continue;
        const map_line_t *l = &m->data.lines[vis[v].line];
        const map_pt_t *p = &m->data.pts[l->first];
        for (int32_t k = 0; k < l->n; k++) buf[k] = xf(t, p[k].x, p[k].y);
        STAT(transforms, l->n);
        polyline(layer, buf, l->n, road_style[l->cls].width, ink[l->cls]);
    }

    /* Route: the part already ridden is a faint trail, the part ahead is bold with a dark casing. */
    if (m->route_lo <= m->route_hi && !KNOB(no_route)) {
        int32_t s = LV_CLAMP(0, m->route_seg, m->data.nroute - 2);
        lv_point_precise_t proj = xf(t, m->proj_x, m->proj_y);
        int32_t n = 0;
        for (int32_t k = LV_MAX(m->route_lo, s - 254); k <= s; k++) buf[n++] = xf(t, m->data.route[k].x, m->data.route[k].y);
        buf[n++] = proj;
        STAT(transforms, n);
        polyline(layer, buf, n, 5, lv_color_mix(m->route_ink, UI_C_BG, LV_OPA_40));

        n = 0;
        buf[n++] = proj;
        for (int32_t k = s + 1; k <= m->route_hi && n < 256; k++) buf[n++] = xf(t, m->data.route[k].x, m->data.route[k].y);
        STAT(transforms, n - 1);
        polyline(layer, buf, n, ROUTE_CASE_W, UI_C_BG);
        polyline(layer, buf, n, ROUTE_W, m->route_ink);
    }

    chevron(layer, t->cx, t->ry, 1.25f, UI_C_BG);
    chevron(layer, t->cx, t->ry, 1.0f, UI_C_TEXT);
}

static void map_delete(lv_event_t *e)
{
    lv_free(lv_obj_get_user_data(lv_event_get_target_obj(e)));
}

/* Snaps the rider onto the nearest route segment so the bold line starts under the chevron. */
static void project_rider(map_t *m)
{
    float best = INFINITY;
    m->route_seg = 0;
    m->proj_x = m->view.x;
    m->proj_y = m->view.y;
    for (int32_t i = 0; i + 1 < m->data.nroute; i++) {
        float ax = m->data.route[i].x, ay = m->data.route[i].y;
        float bx = m->data.route[i + 1].x, by = m->data.route[i + 1].y;
        float dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        float u = len2 > 0 ? ((m->view.x - ax) * dx + (m->view.y - ay) * dy) / len2 : 0;
        u = u < 0 ? 0 : u > 1 ? 1 : u;
        float px = ax + u * dx, py = ay + u * dy;
        float d2 = (px - m->view.x) * (px - m->view.x) + (py - m->view.y) * (py - m->view.y);
        if (d2 < best) {
            best = d2;
            m->route_seg = i;
            m->proj_x = px;
            m->proj_y = py;
        }
    }
}

lv_obj_t *ui_map_create(lv_obj_t *parent)
{
    lv_obj_t *obj = lv_obj_create(parent);
    lv_obj_remove_style_all(obj);
    lv_obj_set_scrollable(obj, false);
    lv_obj_set_clickable(obj, false);
    map_t *m = lv_malloc_zeroed(sizeof(*m));
    m->route_ink = UI_C_ACCENT;
    lv_obj_set_user_data(obj, m);
    lv_obj_add_event_cb(obj, map_draw, LV_EVENT_DRAW_MAIN, NULL);
    lv_obj_add_event_cb(obj, map_delete, LV_EVENT_DELETE, NULL);
    return obj;
}

/*
 * Invalidates the map, less the inside of the cover: the map under it would only be drawn over, and
 * redrawing the cover's own text costs about as much again (on the chip, the card 5 ms a frame). The
 * strips beside the cover take in its rounded corners, where the map shows through: one column a side,
 * because each area that touches the card draws all of it again (four corner squares cost more than two
 * columns).
 */
static void repaint(lv_obj_t *obj, map_t *m)
{
    lv_area_t a, c;
    lv_obj_get_coords(obj, &a);
    if (!m->cover || lv_obj_is_hidden(m->cover)) {
        lv_obj_invalidate_area(obj, &a);
        return;
    }
    lv_obj_get_coords(m->cover, &c);
    if (c.x1 < a.x1 || c.y1 < a.y1 || c.x2 > a.x2 || c.y2 > a.y2) {
        lv_obj_invalidate_area(obj, &a);
        return;
    }
    int32_t r = m->cover_r;
    lv_area_t parts[] = {
        {a.x1, a.y1, a.x2, c.y1 - 1},                     /* above                   */
        {a.x1, c.y2 + 1, a.x2, a.y2},                     /* below                   */
        {a.x1, c.y1, c.x1 + r - 1, c.y2},                 /* left, with both corners */
        {c.x2 - r + 1, c.y1, a.x2, c.y2},                 /* right, with both corners */
    };
    for (size_t i = 0; i < sizeof(parts) / sizeof(parts[0]); i++)
        if (parts[i].x1 <= parts[i].x2 && parts[i].y1 <= parts[i].y2) lv_obj_invalidate_area(obj, &parts[i]);
}

void ui_map_set_cover(lv_obj_t *obj, lv_obj_t *cover, int32_t radius)
{
    map_t *m = lv_obj_get_user_data(obj);
    m->cover = cover;
    m->cover_r = radius;
}

void ui_map_set_data(lv_obj_t *obj, const map_data_t *data)
{
    map_t *m = lv_obj_get_user_data(obj);
    m->data = *data;
    m->data.nlines = LV_MIN(m->data.nlines, UI_MAP_MAX_LINES);
    m->stale = true;
    project_rider(m);
    repaint(obj, m);
}

/*
 * True when no pixel would move by half a pixel or more. Bounds the worst case over the widget: a point
 * r pixels from the rider moves at most |translation| + r * (|rotation| + |relative zoom|).
 */
static bool same_on_screen(const map_view_t *a, const map_view_t *b, float r)
{
    if (a->rider_y != b->rider_y || a->m_per_px <= 0) return false;
    float inv = 1.f / a->m_per_px;
    float dx = (b->x - a->x) * inv, dy = (b->y - a->y) * inv;
    float dh = fmodf(fabsf(b->heading - a->heading), 360.f);
    dh = LV_MIN(dh, 360.f - dh) * (float)M_PI / 180.f;
    float dz = fabsf(b->m_per_px - a->m_per_px) * inv;
    return sqrtf(dx * dx + dy * dy) + r * (dh + dz) < 0.5f;
}

void ui_map_set_view(lv_obj_t *obj, const map_view_t *view)
{
    map_t *m = lv_obj_get_user_data(obj);
    if (!memcmp(&m->view, view, sizeof(*view))) return;
    /* GPS jitter at a standstill would otherwise repaint all 76,800 pixels (30 ms of SPI) each second for
     * no visible change. Compared against the drawn view, so slow drift still adds up to a redraw. */
    int32_t w = lv_obj_get_width(obj), h = lv_obj_get_height(obj);
    int32_t far_y = LV_MAX(view->rider_y, h - view->rider_y);
    if (m->view.m_per_px > 0 && same_on_screen(&m->view, view, sqrtf((float)(w * w / 4 + far_y * far_y)))) return;
    m->view = *view;
    m->stale = true;
    project_rider(m);
    repaint(obj, m);
}

void ui_map_set_route_color(lv_obj_t *obj, lv_color_t c)
{
    map_t *m = lv_obj_get_user_data(obj);
    if (lv_color_eq(m->route_ink, c)) return;
    m->route_ink = c;
    repaint(obj, m);
}
