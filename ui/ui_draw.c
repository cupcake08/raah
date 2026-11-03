#include "ui_draw.h"

#include <math.h>
#include <stdio.h>

#include "ui_theme.h"

/* ---------- pen: maps a 100x100 design grid onto the object, optionally mirrored ---------- */

typedef struct {
    lv_layer_t *layer;
    float ox, oy, s;
    bool mirror;
    lv_color_t ink;
} pen_t;

#define STROKE   13.f
#define HEAD_LEN 30.f
#define HEAD_HW  24.f

static lv_point_precise_t pt(const pen_t *p, float x, float y)
{
    if (p->mirror) x = 100.f - x;
    return (lv_point_precise_t){(lv_value_precise_t)lroundf(p->ox + x * p->s),
                                (lv_value_precise_t)lroundf(p->oy + y * p->s)};
}

static void seg(const pen_t *p, float x1, float y1, float x2, float y2, lv_color_t c)
{
    lv_draw_line_dsc_t d;
    lv_draw_line_dsc_init(&d);
    d.p1 = pt(p, x1, y1);
    d.p2 = pt(p, x2, y2);
    d.color = c;
    d.width = (int32_t)lroundf(STROKE * p->s);
    d.round_start = 1;
    d.round_end = 1;
    lv_draw_line(p->layer, &d);
}

/* Arc along a centreline of radius r; angles in LVGL convention (0 = east, clockwise). */
static void arc(const pen_t *p, float cx, float cy, float r, float a0, float a1, float w, lv_color_t c)
{
    if (p->mirror) {
        float t = 180.f - a1;
        a1 = 180.f - a0;
        a0 = t;
    }
    while (a0 < 0) a0 += 360.f, a1 += 360.f;
    lv_draw_arc_dsc_t d;
    lv_draw_arc_dsc_init(&d);
    lv_point_precise_t ctr = pt(p, cx, cy);
    d.center.x = (int32_t)ctr.x;
    d.center.y = (int32_t)ctr.y;
    d.width = (int32_t)lroundf(w * p->s);
    d.radius = (uint16_t)lroundf((r + w / 2.f) * p->s);
    d.start_angle = (lv_value_precise_t)a0;
    d.end_angle = (lv_value_precise_t)(a1 > 360.f ? a1 - 360.f : a1);
    d.rounded = 1;
    d.color = c;
    lv_draw_arc(p->layer, &d);
}

/* Arrowhead with its tip at (tx,ty) pointing along `deg` (design space, before mirroring). */
static void head(const pen_t *p, float tx, float ty, float deg)
{
    float a = deg * (float)M_PI / 180.f, dx = cosf(a), dy = sinf(a);
    float bx = tx - HEAD_LEN * dx, by = ty - HEAD_LEN * dy;
    lv_draw_triangle_dsc_t d;
    lv_draw_triangle_dsc_init(&d);
    d.p[0] = pt(p, tx, ty);
    d.p[1] = pt(p, bx - HEAD_HW * dy, by + HEAD_HW * dx);
    d.p[2] = pt(p, bx + HEAD_HW * dy, by - HEAD_HW * dx);
    d.color = p->ink;
    d.opa = LV_OPA_COVER;
    lv_draw_triangle(p->layer, &d);
}

static void disc(const pen_t *p, float cx, float cy, float r, lv_color_t c)
{
    lv_draw_rect_dsc_t d;
    lv_draw_rect_dsc_init(&d);
    d.bg_color = c;
    d.radius = LV_RADIUS_CIRCLE;
    lv_point_precise_t ctr = pt(p, cx, cy);
    int32_t rr = (int32_t)lroundf(r * p->s);
    lv_area_t a = {(int32_t)ctr.x - rr, (int32_t)ctr.y - rr, (int32_t)ctr.x + rr, (int32_t)ctr.y + rr};
    lv_draw_rect(p->layer, &d, &a);
}

static lv_color_t dim(lv_color_t ink, lv_color_t bg)
{
    return lv_color_mix(ink, bg, LV_OPA_40);
}

/* ---------- maneuver ---------- */

typedef struct {
    uint8_t man, exit_num;
    lv_color_t ink, bg;
} man_data_t;

static void draw_maneuver(const pen_t *p, const man_data_t *m)
{
    lv_color_t ink = p->ink, faint = dim(ink, m->bg);

    switch (m->man) {
    case MANEUVER_LEFT:
    case MANEUVER_RIGHT:
        seg(p, 64, 94, 64, 52, ink);
        arc(p, 40, 52, 24, 270, 360, STROKE, ink);
        head(p, 8, 28, 180);
        break;
    case MANEUVER_SLIGHT_LEFT:
    case MANEUVER_SLIGHT_RIGHT:
        seg(p, 62, 94, 62, 62, ink);
        seg(p, 62, 62, 38, 38, ink);
        head(p, 20, 20, 225);
        break;
    case MANEUVER_SHARP_LEFT:
    case MANEUVER_SHARP_RIGHT:
        seg(p, 70, 94, 70, 24, ink);
        seg(p, 70, 24, 38, 56, ink);
        head(p, 19, 75, 135);
        break;
    case MANEUVER_UTURN_LEFT:
    case MANEUVER_UTURN_RIGHT:
        seg(p, 68, 94, 68, 46, ink);
        arc(p, 48, 46, 20, 180, 360, STROKE, ink);
        seg(p, 28, 46, 28, 62, ink);
        head(p, 28, 90, 90);
        break;
    case MANEUVER_FORK_LEFT:
    case MANEUVER_FORK_RIGHT:
        seg(p, 58, 64, 80, 42, faint);
        seg(p, 58, 94, 58, 64, ink);
        seg(p, 58, 64, 38, 44, ink);
        head(p, 22, 28, 225);
        break;
    case MANEUVER_MERGE:
        seg(p, 24, 94, 24, 78, faint);
        seg(p, 24, 78, 50, 52, faint);
        seg(p, 50, 94, 50, 34, ink);
        head(p, 50, 6, -90);
        break;
    case MANEUVER_ROUNDABOUT: {
        arc(p, 50, 62, 19, 0, 360, 10, ink);
        seg(p, 50, 96, 50, 84, ink);
        seg(p, 50, 44, 50, 34, ink);
        head(p, 50, 6, -90);
        if (m->exit_num > 0 && m->exit_num < 10) {
            char txt[2] = {(char)('0' + m->exit_num), 0};
            lv_draw_label_dsc_t d;
            lv_draw_label_dsc_init(&d);
            d.font = p->s > 0.7f ? &font_semi_22 : &font_label_12;
            d.color = ink;
            d.align = LV_TEXT_ALIGN_CENTER;
            d.text = txt;
            d.text_local = 1;
            lv_point_precise_t c = pt(p, 50, 62);
            int32_t h = d.font->line_height;
            lv_area_t a = {(int32_t)c.x - h, (int32_t)c.y - h / 2, (int32_t)c.x + h, (int32_t)c.y + h / 2};
            lv_draw_label(p->layer, &d, &a);
        }
        break;
    }
    case MANEUVER_ARRIVE: {
        lv_draw_triangle_dsc_t d;
        lv_draw_triangle_dsc_init(&d);
        d.p[0] = pt(p, 27, 50);
        d.p[1] = pt(p, 73, 50);
        d.p[2] = pt(p, 50, 94);
        d.color = ink;
        d.opa = LV_OPA_COVER;
        lv_draw_triangle(p->layer, &d);
        disc(p, 50, 38, 26, ink);
        disc(p, 50, 38, 10, m->bg);
        break;
    }
    case MANEUVER_STRAIGHT:
    case MANEUVER_UNKNOWN:
    default:
        seg(p, 50, 94, 50, 34, ink);
        head(p, 50, 6, -90);
        break;
    }
}

static bool is_right(uint8_t m)
{
    return m == MANEUVER_RIGHT || m == MANEUVER_SLIGHT_RIGHT || m == MANEUVER_SHARP_RIGHT ||
           m == MANEUVER_UTURN_RIGHT || m == MANEUVER_FORK_RIGHT;
}

static void maneuver_event(lv_event_t *e)
{
    lv_obj_t *obj = lv_event_get_target_obj(e);
    man_data_t *m = lv_obj_get_user_data(obj);
    if (lv_event_get_code(e) == LV_EVENT_DELETE) {
        lv_free(m);
        return;
    }
    lv_area_t a;
    lv_obj_get_content_coords(obj, &a);
    float size = (float)LV_MIN(lv_area_get_width(&a), lv_area_get_height(&a));
    pen_t p = {
        .layer = lv_event_get_layer(e),
        .ox = a.x1 + (lv_area_get_width(&a) - size) / 2.f,
        .oy = a.y1 + (lv_area_get_height(&a) - size) / 2.f,
        .s = size / 100.f,
        .mirror = is_right(m->man),
        .ink = m->ink,
    };
    draw_maneuver(&p, m);
}

lv_obj_t *ui_maneuver_create(lv_obj_t *parent, int32_t size)
{
    lv_obj_t *obj = lv_obj_create(parent);
    lv_obj_remove_style_all(obj);
    lv_obj_set_size(obj, size, size);
    man_data_t *m = lv_malloc_zeroed(sizeof(*m));
    m->ink = UI_C_ACCENT;
    m->bg = UI_C_CARD;
    lv_obj_set_user_data(obj, m);
    lv_obj_add_event_cb(obj, maneuver_event, LV_EVENT_DRAW_MAIN, NULL);
    lv_obj_add_event_cb(obj, maneuver_event, LV_EVENT_DELETE, NULL);
    return obj;
}

void ui_maneuver_set(lv_obj_t *obj, uint8_t maneuver, uint8_t exit_num, lv_color_t ink, lv_color_t bg)
{
    man_data_t *m = lv_obj_get_user_data(obj);
    if (m->man == maneuver && m->exit_num == exit_num && lv_color_eq(m->ink, ink) && lv_color_eq(m->bg, bg)) return;
    m->man = maneuver;
    m->exit_num = exit_num;
    m->ink = ink;
    m->bg = bg;
    lv_obj_invalidate(obj);
}

/* ---------- bluetooth rune ---------- */

static void ble_draw(lv_event_t *e)
{
    lv_obj_t *obj = lv_event_get_target_obj(e);
    lv_area_t a;
    lv_obj_get_content_coords(obj, &a);
    int32_t w = lv_area_get_width(&a), h = lv_area_get_height(&a);
    int32_t x0 = a.x1, x1 = a.x1 + w - 1, xm = a.x1 + w / 2, y0 = a.y1, y1 = a.y2;
    int32_t q1 = a.y1 + h / 4, q3 = a.y1 + h * 3 / 4;
    lv_point_precise_t pts[6] = {{x0, q1}, {x1, q3}, {xm, y1}, {xm, y0}, {x1, q1}, {x0, q3}};

    lv_draw_line_dsc_t d;
    lv_draw_line_dsc_init(&d);
    d.color = lv_obj_get_style_line_color(obj, LV_PART_MAIN);
    d.width = lv_obj_get_style_line_width(obj, LV_PART_MAIN);
    d.opa = lv_obj_get_style_line_opa(obj, LV_PART_MAIN);
    d.round_start = 1;
    d.round_end = 1;
    lv_layer_t *layer = lv_event_get_layer(e);
    for (int i = 0; i < 5; i++) {
        d.p1 = pts[i];
        d.p2 = pts[i + 1];
        lv_draw_line(layer, &d);
    }
}

lv_obj_t *ui_ble_create(lv_obj_t *parent, int32_t w, int32_t h)
{
    lv_obj_t *obj = lv_obj_create(parent);
    lv_obj_remove_style_all(obj);
    lv_obj_set_size(obj, w, h);
    lv_obj_set_style_line_width(obj, 2, 0);
    lv_obj_set_style_line_color(obj, UI_C_TEXT, 0);
    lv_obj_add_event_cb(obj, ble_draw, LV_EVENT_DRAW_MAIN, NULL);
    return obj;
}

/* ---------- battery ---------- */

#define BATT_W 24
#define BATT_H 12

static void battery_draw(lv_event_t *e)
{
    lv_obj_t *obj = lv_event_get_target_obj(e);
    intptr_t v = (intptr_t)lv_obj_get_user_data(obj);
    int pct = (int)(v & 0xFF) - 1;
    bool charging = v & 0x100;
    lv_layer_t *layer = lv_event_get_layer(e);
    lv_area_t a;
    lv_obj_get_coords(obj, &a);

    lv_draw_rect_dsc_t d;
    lv_draw_rect_dsc_init(&d);
    lv_area_t body = {a.x1, a.y1, a.x1 + BATT_W - 4, a.y1 + BATT_H - 1};
    d.bg_opa = LV_OPA_TRANSP;
    d.border_color = UI_C_TEXT2;
    d.border_width = 1;
    d.radius = 3;
    lv_draw_rect(layer, &d, &body);

    lv_area_t nub = {body.x2 + 1, a.y1 + 4, body.x2 + 2, a.y1 + BATT_H - 5};
    d.bg_opa = LV_OPA_COVER;
    d.bg_color = UI_C_TEXT2;
    d.border_width = 0;
    d.radius = 1;
    lv_draw_rect(layer, &d, &nub);

    if (pct < 0) return;
    int32_t inner_w = lv_area_get_width(&body) - 4;
    int32_t fill = LV_MAX(2, inner_w * LV_MIN(pct, 100) / 100);
    lv_area_t f = {body.x1 + 2, body.y1 + 2, body.x1 + 1 + fill, body.y2 - 2};
    d.bg_color = charging ? UI_C_ACCENT : pct <= 10 ? UI_C_ALERT : pct <= 20 ? UI_C_NEAR : UI_C_TEXT;
    d.radius = 1;
    lv_draw_rect(layer, &d, &f);
}

lv_obj_t *ui_battery_create(lv_obj_t *parent)
{
    lv_obj_t *obj = lv_obj_create(parent);
    lv_obj_remove_style_all(obj);
    lv_obj_set_size(obj, BATT_W, BATT_H);
    lv_obj_add_event_cb(obj, battery_draw, LV_EVENT_DRAW_MAIN, NULL);
    return obj;
}

void ui_battery_set(lv_obj_t *obj, int pct, bool charging)
{
    intptr_t v = (intptr_t)(LV_CLAMP(-1, pct, 100) + 1) | (charging ? 0x100 : 0);
    if ((intptr_t)lv_obj_get_user_data(obj) == v) return;
    lv_obj_set_user_data(obj, (void *)v);
    lv_obj_invalidate(obj);
}
