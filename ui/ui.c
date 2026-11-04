#include "ui.h"

#include <ctype.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#include "ui_draw.h"
#include "ui_map.h"
#include "ui_theme.h"

#define SB_H        28
#define CARD_X      8
#define CARD_Y      32
#define CARD_H      180
#define ICON_Y      6
#define DIST_BASE   152
#define BAR_Y       164
#define STREET_Y    218
#define TRIP_LINE_Y 288
#define TRIP_BASE   311
#define ICON_SLOTS  3

/* Map view: a full-bleed map, faded to black under the status bar, with the turn on a card floating
 * at the bottom, over the road already ridden that the map would otherwise show behind the rider. */
#define MCARD_M        8      /* inset from the screen edges */
#define MCARD_H        86
#define MCARD_BORDER   2
#define MCARD_PAD      10
#define MAP_FADE_H     46
#define RIDER_ABOVE_CARD 40
#define STOPPED_KMH_X10  30
#define POSE_MAX_MS    1200   /* longest glide between two poses */
#define POSE_JUMP_PX   120    /* further than this, the map cuts instead of gliding */
#define GLIDE_STEP_PX  2.f    /* least on-screen change worth a frame mid-glide */
#define CALM_STEP_PX   6.f    /* ...with the screen dimmed, where nobody is watching closely */
#define CALM_FRAME_MS  250    /* ...and no more than 4 of them a second */

typedef struct {
    lv_obj_t *num, *unit;
} pair_t;

static struct {
    int32_t w, h;
    bool imperial, use_24h, anims, calm;
    bool connected, sleeping;
    nav_state_t nav;
    uint32_t step_key, step_start;
    int batt_pct;
    bool charging;
    int hour, minute, wday, mday, month;

    lv_obj_t *cur;
    /* status bar (on the top layer, shared by all screens) */
    lv_obj_t *sb, *sb_clock, *sb_pct, *sb_batt;
    pair_t sb_speed;
    /* waiting */
    lv_obj_t *scr_wait, *ring[2];
    /* idle */
    lv_obj_t *scr_idle, *idle_date;
    pair_t idle_clock, idle_speed;
    /* navigating */
    lv_obj_t *scr_nav, *card, *icon_box, *man_vec, *man_img, *spinner, *reroute, *bar, *primary, *secondary;
    pair_t dist, trip[3];
    /* arrived */
    lv_obj_t *scr_arrive, *arr_dest;
    /* map */
    lv_obj_t *scr_map, *map, *map_card, *map_icon, *map_street, *map_trip, *map_arc;
    pair_t map_dist;
    bool round, has_map, has_pose, prefer_cards;
    map_view_t pose, pose_from, pose_to, pose_drawn;   /* now, the glide between the last two from the phone */
    uint32_t pose_t0, pose_ms, pose_rx, pose_drawn_at;
    bool pose_cut;
    lv_timer_t *pose_timer;
    /* sleep */
    lv_obj_t *scr_sleep;
    lv_obj_t *toast, *toast_lbl;
    lv_timer_t *toast_timer;

    uint32_t icon_hash[ICON_SLOTS];
    uint8_t icon_next;
    lv_image_dsc_t icon_dsc[ICON_SLOTS];
} ui;

static uint8_t icon_buf[ICON_SLOTS][NAV_ICON_BYTES];

/* ---------- helpers ---------- */

static lv_obj_t *box(lv_obj_t *parent)
{
    lv_obj_t *o = lv_obj_create(parent);
    lv_obj_remove_style_all(o);
    lv_obj_set_scrollable(o, false);
    lv_obj_set_clickable(o, false);
    return o;
}

static lv_obj_t *screen(void)
{
    lv_obj_t *s = lv_obj_create(NULL);
    lv_obj_remove_style_all(s);
    lv_obj_set_scrollable(s, false);
    lv_obj_set_style_bg_color(s, UI_C_BG, 0);
    lv_obj_set_style_bg_opa(s, LV_OPA_COVER, 0);
    return s;
}

static lv_obj_t *label(lv_obj_t *parent, const lv_font_t *font, lv_color_t color, const char *txt)
{
    lv_obj_t *l = lv_label_create(parent);
    lv_obj_set_style_text_font(l, font, 0);
    lv_obj_set_style_text_color(l, color, 0);
    lv_label_set_text(l, txt);
    return l;
}

static void free_src(lv_event_t *e)
{
    lv_free(lv_obj_get_user_data(lv_event_get_target_obj(e)));
}

/*
 * lv_label_set_text() frees, reallocates and invalidates even when the text is unchanged, and the map
 * under a card repaints with it. The label's own text can't be compared: dot truncation rewrites it, so
 * the last text set is kept in the label's user data.
 */
static void set_text(lv_obj_t *l, const char *txt)
{
    char *src = lv_obj_get_user_data(l);
    if (src && !strcmp(src, txt)) return;
    if (!src) lv_obj_add_event_cb(l, free_src, LV_EVENT_DELETE, NULL);
    lv_free(src);
    lv_obj_set_user_data(l, lv_strdup(txt));
    lv_label_set_text(l, txt);
}

static int32_t text_w(lv_obj_t *l, const char *t)
{
    lv_point_t sz;
    lv_text_get_size(&sz, t, lv_obj_get_style_text_font(l, 0), lv_obj_get_style_text_letter_space(l, 0), 0,
                     LV_COORD_MAX, LV_TEXT_FLAG_NONE);
    return sz.x;
}

/* y of a label's top edge so that its baseline lands on `baseline`. */
static int32_t top_for(lv_obj_t *l, int32_t baseline)
{
    const lv_font_t *f = lv_obj_get_style_text_font(l, 0);
    return baseline - (f->line_height - f->base_line);
}

static void pair_create(pair_t *p, lv_obj_t *parent, const lv_font_t *fn, lv_color_t cn, const lv_font_t *fu,
                        lv_color_t cu)
{
    p->num = label(parent, fn, cn, "");
    p->unit = label(parent, fu, cu, "");
}

/* Figure + unit sharing a baseline, centred as a group on cx. */
static void pair_place(pair_t *p, const char *num, const char *unit, int32_t cx, int32_t baseline, int32_t gap)
{
    set_text(p->num, num);
    set_text(p->unit, unit);
    int32_t wn = text_w(p->num, num), wu = unit[0] ? text_w(p->unit, unit) : 0;
    int32_t x = cx - (wn + (unit[0] ? gap : 0) + wu) / 2;
    lv_obj_set_pos(p->num, x, top_for(p->num, baseline));
    lv_obj_set_pos(p->unit, x + wn + gap, top_for(p->unit, baseline));
}

static int32_t pair_width(pair_t *p, const char *num, const char *unit, int32_t gap)
{
    return text_w(p->num, num) + (unit[0] ? gap + text_w(p->unit, unit) : 0);
}

/* Style setters invalidate even when the value is unchanged, so the per-update ones check first. */
static void set_ink(lv_obj_t *o, lv_color_t c)
{
    if (!lv_color_eq(lv_obj_get_style_text_color(o, 0), c)) lv_obj_set_style_text_color(o, c, 0);
}

static void set_bg(lv_obj_t *o, lv_color_t c, lv_style_selector_t sel)
{
    if (!lv_color_eq(lv_obj_get_style_bg_color(o, sel), c)) lv_obj_set_style_bg_color(o, c, sel);
}

static void pair_color(pair_t *p, lv_color_t cn, lv_color_t cu)
{
    set_ink(p->num, cn);
    set_ink(p->unit, cu);
}

/* ---------- text fitting ---------- */

static uint32_t str_hash(const char *s)
{
    uint32_t h = 2166136261u;
    while (*s) h = (h ^ (uint8_t)*s++) * 16777619u;
    return h;
}

typedef struct {
    const lv_font_t *font;
    uint8_t lines;
} fit_t;

/* Largest first. Names step down a size before they wrap. */
static const fit_t FIT_PRIMARY[] = {{&font_semi_26, 1}, {&font_semi_22, 1}, {&font_semi_22, 2}, {&font_med_18, 2}};
static const fit_t FIT_DEST[] = {{&font_semi_22, 1}, {&font_semi_22, 2}, {&font_med_18, 2}};
static const fit_t FIT_LINE[] = {{&font_med_18, 1}};
static const fit_t FIT_SECOND[] = {{&font_med_18, 1}, {&font_med_18, 2}};
#define FITS(a) (a), (int)(sizeof(a) / sizeof((a)[0]))

/*
 * Map card layouts, largest first. The card keeps its size; a longer name takes a smaller distance and
 * a smaller, wrapped name instead, and the last layout moves the name under the turn, across the card.
 */
typedef struct {
    int32_t icon;
    const lv_font_t *num, *unit;
    bool wide;                  /* name below the arrow row rather than beside the arrow */
    const fit_t *name;
    int nname;
} card_fit_t;

static const fit_t CARD_NAME_1[] = {{&font_semi_22, 1}, {&font_med_18, 1}};
static const fit_t CARD_NAME_2[] = {{&font_med_18, 2}};
static const card_fit_t CARD_FITS[] = {
    {56, &font_num_64, &font_semi_26, false, FITS(CARD_NAME_1)},
    {48, &font_num_44, &font_semi_22, false, FITS(CARD_NAME_2)},
    {36, &font_num_44, &font_semi_22, true, FITS(CARD_NAME_2)},
};
#define CARD_FIT_N ((int)(sizeof(CARD_FITS) / sizeof(CARD_FITS[0])))

static const char *const abbr[][2] = {
    {"Road", "Rd"},       {"Street", "St"}, {"Avenue", "Ave"},  {"Lane", "Ln"},    {"Highway", "Hwy"},
    {"Boulevard", "Blvd"}, {"Drive", "Dr"},  {"Block", "Blk"},   {"Junction", "Jn"}, {"Circle", "Cir"},
    {"Cross", "Cr"},      {"towards", "to"}, {"toward", "to"},
};

/* Whole-word abbreviations: "Outer Ring Road Service Lane" -> "Outer Ring Rd Service Ln". */
static void abbreviate(char *out, size_t n, const char *s)
{
    size_t o = 0;
    while (*s && o + 1 < n) {
        size_t len = strcspn(s, " ,"), wl = len;
        const char *w = s;
        for (size_t k = 0; k < sizeof(abbr) / sizeof(abbr[0]); k++) {
            if (strlen(abbr[k][0]) == len && !strncmp(s, abbr[k][0], len)) {
                w = abbr[k][1];
                wl = strlen(w);
                break;
            }
        }
        for (size_t i = 0; i < wl && o + 1 < n; i++) out[o++] = w[i];
        for (s += len; (*s == ' ' || *s == ',') && o + 1 < n; s++) out[o++] = *s;
    }
    out[o] = '\0';
}

static bool fits(const char *t, const fit_t *f, int32_t w)
{
    lv_point_t sz;
    lv_text_get_size(&sz, t, f->font, 0, 0, f->lines == 1 ? LV_COORD_MAX : w, LV_TEXT_FLAG_NONE);
    return f->lines == 1 ? sz.x <= w : sz.y <= f->lines * f->font->line_height;
}

/*
 * Shows `txt` whole in the first option no taller than `max_h` that holds it. Smaller sizes on as many
 * lines come before the abbreviated form, which reads less easily ("2nd Cross Road", not "2nd Cr Rd");
 * text that fits none is cut, with dots, in the last such option.
 * Returns the height used; `whole` (optional) says whether nothing was cut.
 */
static int32_t fit_text(lv_obj_t *l, const char *txt, const fit_t *fit, int n, int32_t w, int32_t max_h,
                        bool *whole)
{
    /* Measuring is a few ms of glyph walking per call; most updates repeat the last answer. */
    static struct {
        lv_obj_t *l;
        uint32_t key;
        int32_t h;
        bool whole;
    } memo[6];
    uint32_t key = str_hash(txt) ^ (uint32_t)(uintptr_t)fit ^ (uint32_t)n << 28 ^ (uint32_t)w << 14 ^ (uint32_t)max_h;
    int slot = 0;
    while (slot < 5 && memo[slot].l && memo[slot].l != l) slot++;
    if (memo[slot].l == l && memo[slot].key == key) {
        if (whole) *whole = memo[slot].whole;
        return memo[slot].h;
    }

    char ab[NAV_SECONDARY_MAX + 1];
    abbreviate(ab, sizeof(ab), txt);
    bool has_ab = strcmp(ab, txt) != 0;
    const fit_t *f = &fit[0];
    const char *t = has_ab ? ab : txt;
    bool cut = true;
    for (int i = 0, g = 0; i < n && cut; i++) {
        if (i && fit[i].lines != fit[i - 1].lines) g = i;     /* first option with this many lines */
        if (fit[i].font->line_height * fit[i].lines > max_h) continue;
        f = &fit[i];
        if (fits(txt, f, w)) t = txt, cut = false;
        else if (has_ab && (i + 1 == n || fit[i + 1].lines != fit[i].lines))
            for (int j = g; j <= i && cut; j++)
                if (fit[j].font->line_height * fit[j].lines <= max_h && fits(ab, &fit[j], w)) f = &fit[j], t = ab, cut = false;
    }
    if (whole) *whole = !cut;
    lv_label_long_mode_t mode = cut ? LV_LABEL_LONG_MODE_DOTS : LV_LABEL_LONG_MODE_WRAP;
    if (lv_obj_get_style_text_font(l, 0) != f->font) lv_obj_set_style_text_font(l, f->font, 0);
    if (lv_label_get_long_mode(l) != mode) lv_label_set_long_mode(l, mode);
    int32_t h = f->font->line_height * f->lines;
    lv_obj_set_size(l, w, h);
    set_text(l, t);
    memo[slot].l = l;
    memo[slot].key = key;
    memo[slot].h = h;
    memo[slot].whole = !cut;
    return h;
}

static void set_visible(lv_obj_t *o, bool v)
{
    lv_obj_set_hidden(o, !v);
}

static void upper(char *s)
{
    for (; *s; s++) *s = (char)toupper((unsigned char)*s);
}

/* ---------- animations ---------- */

#define RING_MIN 64
#define RING_MAX 150
#define RING_CY  146

static void ring_exec(void *var, int32_t v)
{
    lv_obj_t *r = var;
    lv_obj_set_size(r, v, v);
    lv_obj_set_pos(r, ui.w / 2 - v / 2, RING_CY - v / 2);
    lv_obj_set_style_opa(r, (lv_opa_t)(200 * (RING_MAX - v) / (RING_MAX - RING_MIN)), 0);
}

static void rings_run(bool run)
{
    for (int i = 0; i < 2; i++) {
        lv_anim_delete(ui.ring[i], ring_exec);
        ring_exec(ui.ring[i], RING_MAX);
        if (!run) continue;
        lv_anim_t a;
        lv_anim_init(&a);
        lv_anim_set_var(&a, ui.ring[i]);
        lv_anim_set_exec_cb(&a, ring_exec);
        lv_anim_set_values(&a, RING_MIN, RING_MAX);
        lv_anim_set_duration(&a, 2400);
        lv_anim_set_delay(&a, i * 1200);
        lv_anim_set_path_cb(&a, lv_anim_path_ease_out);
        lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
        lv_anim_start(&a);
    }
}

static void icon_in_exec(void *var, int32_t v)
{
    lv_obj_set_style_translate_y(var, 18 - 18 * v / 256, 0);
    lv_obj_set_style_opa(var, (lv_opa_t)LV_MIN(v, 255), 0);
}

/* The spinner's animation runs even while hidden, so it only exists while needed. */
static void spinner_set(bool on)
{
    if (on && !ui.spinner) {
        ui.spinner = lv_spinner_create(ui.icon_box);
        lv_obj_set_size(ui.spinner, 64, 64);
        lv_obj_center(ui.spinner);
        lv_spinner_set_anim_params(ui.spinner, 1100, 240);
        lv_obj_set_style_arc_width(ui.spinner, 8, LV_PART_MAIN);
        lv_obj_set_style_arc_width(ui.spinner, 8, LV_PART_INDICATOR);
        lv_obj_set_style_arc_color(ui.spinner, UI_C_LINE, LV_PART_MAIN);
        lv_obj_set_style_arc_color(ui.spinner, UI_C_LINK, LV_PART_INDICATOR);
    } else if (!on && ui.spinner) {
        lv_obj_delete(ui.spinner);
        ui.spinner = NULL;
    }
}

static void icon_animate_in(void)
{
    lv_anim_delete(ui.icon_box, icon_in_exec);
    if (!ui.anims || ui.cur != ui.scr_nav) {
        icon_in_exec(ui.icon_box, 256);
        return;
    }
    lv_anim_t a;
    lv_anim_init(&a);
    lv_anim_set_var(&a, ui.icon_box);
    lv_anim_set_exec_cb(&a, icon_in_exec);
    lv_anim_set_values(&a, 0, 256);
    lv_anim_set_duration(&a, 320);
    lv_anim_set_path_cb(&a, lv_anim_path_ease_out);
    lv_anim_start(&a);
}

/* ---------- status bar ---------- */

static void build_statusbar(void)
{
    ui.sb = box(lv_layer_top());
    lv_obj_set_size(ui.sb, ui.w, SB_H);

    ui.sb_clock = label(ui.sb, &font_med_18, UI_C_TEXT, "--:--");
    lv_obj_set_pos(ui.sb_clock, 14, top_for(ui.sb_clock, 20));

    pair_create(&ui.sb_speed, ui.sb, &font_semi_22, UI_C_TEXT, &font_label_12, UI_C_TEXT2);
    lv_obj_set_style_text_letter_space(ui.sb_speed.unit, 1, 0);

    ui.sb_batt = ui_battery_create(ui.sb);
    lv_obj_set_pos(ui.sb_batt, ui.w - 14 - 24, 8);
    ui.sb_pct = label(ui.sb, &font_label_12, UI_C_TEXT2, "");
}

static void update_statusbar(void)
{
    char buf[16];
    nav_fmt_clock(ui.hour, ui.minute, ui.use_24h, buf, sizeof(buf));
    set_text(ui.sb_clock, buf);

    ui_battery_set(ui.sb_batt, ui.batt_pct, ui.charging);
    if (ui.batt_pct >= 0) snprintf(buf, sizeof(buf), "%d%%", ui.batt_pct);
    else buf[0] = '\0';
    set_text(ui.sb_pct, buf);
    int32_t pct_x = ui.w - 14 - 24 - 6 - text_w(ui.sb_pct, buf);
    lv_obj_set_pos(ui.sb_pct, pct_x, top_for(ui.sb_pct, 18));

    int spd = nav_speed_display(ui.nav.speed_kmh_x10, ui.imperial);
    bool show_speed = !ui.round && (ui.cur == ui.scr_nav || ui.cur == ui.scr_map) && spd >= 0 &&
                      !(ui.nav.flags & NAV_F_REROUTING);
    set_visible(ui.sb_speed.num, show_speed);
    set_visible(ui.sb_speed.unit, show_speed);
    if (show_speed) {
        snprintf(buf, sizeof(buf), "%d", spd);
        pair_place(&ui.sb_speed, buf, ui.imperial ? "MPH" : "KM/H", ui.w / 2, 21, 4);
    }

    bool sleeping = ui.cur == ui.scr_sleep;
    set_visible(ui.sb, !sleeping);
    set_visible(ui.sb_clock, ui.cur == ui.scr_nav || ui.cur == ui.scr_arrive || ui.cur == ui.scr_map);

    if (ui.round) {
        /* Corners are outside the glass: clock and charge sit together at the top centre. */
        set_visible(ui.sb_batt, false);
        const char *clk = lv_label_get_text(ui.sb_clock), *pct = lv_label_get_text(ui.sb_pct);
        bool clk_on = !lv_obj_is_hidden(ui.sb_clock);
        int32_t wc = clk_on ? text_w(ui.sb_clock, clk) : 0, wp = text_w(ui.sb_pct, pct);
        int32_t x = ui.w / 2 - (wc + (clk_on ? 10 : 0) + wp) / 2;
        lv_obj_set_pos(ui.sb_clock, x, top_for(ui.sb_clock, 40));
        lv_obj_set_pos(ui.sb_pct, x + wc + (clk_on ? 10 : 0), top_for(ui.sb_pct, 39));
    }
}

/* ---------- screens ---------- */

static void build_wait(void)
{
    ui.scr_wait = screen();
    pair_t mark;
    pair_create(&mark, ui.scr_wait, &font_bold_28, UI_C_TEXT, &font_bold_28, UI_C_ACCENT);
    lv_obj_set_style_text_letter_space(mark.num, 4, 0);
    lv_obj_set_style_text_letter_space(mark.unit, 4, 0);
    pair_place(&mark, "RAAH", ".", ui.w / 2, 66, 0);

    for (int i = 0; i < 2; i++) {
        ui.ring[i] = box(ui.scr_wait);
        lv_obj_set_style_radius(ui.ring[i], LV_RADIUS_CIRCLE, 0);
        lv_obj_set_style_border_width(ui.ring[i], 2, 0);
        lv_obj_set_style_border_color(ui.ring[i], UI_C_LINK, 0);
        ring_exec(ui.ring[i], RING_MAX);
    }
    lv_obj_t *disc = box(ui.scr_wait);
    lv_obj_set_size(disc, RING_MIN, RING_MIN);
    lv_obj_set_pos(disc, ui.w / 2 - RING_MIN / 2, RING_CY - RING_MIN / 2);
    lv_obj_set_style_radius(disc, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_color(disc, UI_C_CARD, 0);
    lv_obj_set_style_bg_opa(disc, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(disc, 1, 0);
    lv_obj_set_style_border_color(disc, UI_C_CARD_EDGE, 0);
    lv_obj_t *rune = ui_ble_create(disc, 16, 28);
    lv_obj_set_style_line_width(rune, 3, 0);
    lv_obj_set_style_line_color(rune, UI_C_LINK, 0);
    lv_obj_center(rune);

    lv_obj_t *t = label(ui.scr_wait, &font_semi_22, UI_C_TEXT, "Waiting for phone");
    lv_obj_set_width(t, ui.w);
    lv_obj_set_style_text_align(t, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(t, 0, top_for(t, 246));

    lv_obj_t *s = label(ui.scr_wait, &font_med_18, UI_C_TEXT2, "Open Raah on your phone\nand start a route");
    lv_obj_set_width(s, ui.w);
    lv_obj_set_style_text_align(s, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_style_text_line_space(s, 2, 0);
    lv_obj_set_pos(s, 0, 256);
}

static void build_idle(void)
{
    ui.scr_idle = screen();
    ui.idle_date = label(ui.scr_idle, &font_label_12, UI_C_TEXT2, "");
    lv_obj_set_style_text_letter_space(ui.idle_date, 2, 0);
    lv_obj_set_width(ui.idle_date, ui.w);
    lv_obj_set_style_text_align(ui.idle_date, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(ui.idle_date, 0, top_for(ui.idle_date, 52));

    pair_create(&ui.idle_clock, ui.scr_idle, &font_num_96, UI_C_TEXT, &font_semi_22, UI_C_TEXT2);

    lv_obj_t *card = box(ui.scr_idle);
    lv_obj_set_pos(card, 16, 158);
    lv_obj_set_size(card, ui.w - 32, 92);
    lv_obj_set_style_radius(card, 20, 0);
    lv_obj_set_style_bg_color(card, UI_C_CARD, 0);
    lv_obj_set_style_bg_opa(card, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(card, 1, 0);
    lv_obj_set_style_border_color(card, UI_C_CARD_EDGE, 0);
    lv_obj_t *cap = label(card, &font_label_12, UI_C_TEXT3, "SPEED");
    lv_obj_set_style_text_letter_space(cap, 2, 0);
    lv_obj_set_width(cap, ui.w - 32);
    lv_obj_set_style_text_align(cap, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(cap, 0, top_for(cap, 22));
    pair_create(&ui.idle_speed, card, &font_num_64, UI_C_TEXT, &font_semi_22, UI_C_TEXT2);

    lv_obj_t *hint = label(ui.scr_idle, &font_med_18, UI_C_TEXT3, "Start a route in Google Maps");
    lv_obj_set_width(hint, ui.w);
    lv_obj_set_style_text_align(hint, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(hint, 0, 276);
}

static void build_nav(void)
{
    ui.scr_nav = screen();
    int32_t cw = ui.w - 2 * CARD_X;

    ui.card = box(ui.scr_nav);
    lv_obj_set_pos(ui.card, CARD_X, CARD_Y);
    lv_obj_set_size(ui.card, cw, CARD_H);
    lv_obj_set_style_radius(ui.card, 22, 0);
    lv_obj_set_style_bg_opa(ui.card, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(ui.card, 1, 0);

    ui.icon_box = box(ui.card);
    lv_obj_set_size(ui.icon_box, NAV_ICON_PX, NAV_ICON_PX);
    lv_obj_set_pos(ui.icon_box, (cw - NAV_ICON_PX) / 2, ICON_Y);
    ui.man_vec = ui_maneuver_create(ui.icon_box, NAV_ICON_PX);
    ui.man_img = lv_image_create(ui.icon_box);
    lv_obj_set_style_image_recolor_opa(ui.man_img, LV_OPA_COVER, 0);

    pair_create(&ui.dist, ui.card, &font_num_64, UI_C_TEXT, &font_semi_26, UI_C_TEXT2);
    ui.reroute = label(ui.card, &font_bold_28, UI_C_LINK, "Rerouting");
    lv_obj_set_width(ui.reroute, cw);
    lv_obj_set_style_text_align(ui.reroute, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(ui.reroute, 0, top_for(ui.reroute, 146));

    ui.bar = lv_bar_create(ui.card);
    lv_obj_remove_style_all(ui.bar);
    lv_obj_set_pos(ui.bar, 22, BAR_Y);
    lv_obj_set_size(ui.bar, cw - 44, 5);
    lv_bar_set_range(ui.bar, 0, 1000);
    lv_obj_set_style_bg_opa(ui.bar, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(ui.bar, LV_OPA_COVER, LV_PART_INDICATOR);
    lv_obj_set_style_radius(ui.bar, 3, LV_PART_MAIN);
    lv_obj_set_style_radius(ui.bar, 3, LV_PART_INDICATOR);

    ui.primary = label(ui.scr_nav, &font_semi_26, UI_C_TEXT, "");
    lv_obj_set_pos(ui.primary, 14, STREET_Y);
    ui.secondary = label(ui.scr_nav, &font_med_18, UI_C_TEXT2, "");

    lv_obj_t *line = box(ui.scr_nav);
    lv_obj_set_pos(line, 14, TRIP_LINE_Y);
    lv_obj_set_size(line, ui.w - 28, 1);
    lv_obj_set_style_bg_color(line, UI_C_LINE, 0);
    lv_obj_set_style_bg_opa(line, LV_OPA_COVER, 0);

    for (int i = 0; i < 3; i++) {
        pair_create(&ui.trip[i], ui.scr_nav, &font_semi_22, UI_C_TEXT, &font_label_12, UI_C_TEXT2);
        lv_obj_set_style_text_letter_space(ui.trip[i].unit, 1, 0);
    }
}

static void build_arrive(void)
{
    ui.scr_arrive = screen();
    lv_obj_t *pin = ui_maneuver_create(ui.scr_arrive, 104);
    lv_obj_set_pos(pin, (ui.w - 104) / 2, 52);
    ui_maneuver_set(pin, MANEUVER_ARRIVE, 0, UI_C_ACCENT, UI_C_BG);

    lv_obj_t *t = label(ui.scr_arrive, &font_bold_28, UI_C_TEXT, "You've arrived");
    lv_obj_set_width(t, ui.w);
    lv_obj_set_style_text_align(t, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(t, 0, top_for(t, 196));

    ui.arr_dest = label(ui.scr_arrive, &font_semi_22, UI_C_TEXT2, "");
    lv_obj_set_style_text_align(ui.arr_dest, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(ui.arr_dest, 20, 210);
}

static void build_sleep(void)
{
    ui.scr_sleep = screen();
    lv_obj_t *t = label(ui.scr_sleep, &font_semi_22, UI_C_TEXT2, "Sleeping");
    lv_obj_set_width(t, ui.w);
    lv_obj_set_style_text_align(t, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(t, 0, top_for(t, 156));
    lv_obj_t *s = label(ui.scr_sleep, &font_label_12, UI_C_TEXT3, "PRESS THE BUTTON TO WAKE");
    lv_obj_set_style_text_letter_space(s, 1, 0);
    lv_obj_set_width(s, ui.w);
    lv_obj_set_style_text_align(s, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(s, 0, top_for(s, 182));
}

static void build_toast(void)
{
    ui.toast = box(lv_layer_top());
    lv_obj_set_size(ui.toast, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
    lv_obj_set_style_bg_color(ui.toast, lv_color_hex(0x2A3038), 0);
    lv_obj_set_style_bg_opa(ui.toast, LV_OPA_COVER, 0);
    lv_obj_set_style_radius(ui.toast, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_pad_hor(ui.toast, 16, 0);
    lv_obj_set_style_pad_ver(ui.toast, 7, 0);
    ui.toast_lbl = label(ui.toast, &font_med_18, UI_C_TEXT, "");
    lv_obj_align(ui.toast, LV_ALIGN_BOTTOM_MID, 0, -48);
    lv_obj_set_hidden(ui.toast, true);
}

static void build_map(void)
{
    ui.scr_map = screen();
    ui.map = ui_map_create(ui.scr_map);
    lv_obj_set_size(ui.map, ui.w, ui.h);

    if (!ui.round) {
        /* The map runs up under the status bar; a fade keeps the clock and speed legible over it. With
         * 40-row strips the status bar is drawn once a frame, so this costs about 2 ms of a riding frame. */
        lv_obj_t *top = box(ui.scr_map);
        lv_obj_set_size(top, ui.w, MAP_FADE_H);
        lv_obj_set_style_bg_opa(top, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_color(top, UI_C_BG, 0);
        lv_obj_set_style_bg_grad_color(top, UI_C_BG, 0);
        lv_obj_set_style_bg_grad_dir(top, LV_GRAD_DIR_VER, 0);
        lv_obj_set_style_bg_main_opa(top, LV_OPA_COVER, 0);
        lv_obj_set_style_bg_grad_opa(top, LV_OPA_TRANSP, 0);

        /* Pure black card: a TN panel seen from its bad side lifts dark greys to a pale wash. */
        ui.map_card = box(ui.scr_map);
        lv_obj_set_pos(ui.map_card, MCARD_M, ui.h - MCARD_M - MCARD_H);
        lv_obj_set_size(ui.map_card, ui.w - 2 * MCARD_M, MCARD_H);
        lv_obj_set_style_radius(ui.map_card, 20, 0);
        lv_obj_set_style_bg_color(ui.map_card, UI_C_BG, 0);
        lv_obj_set_style_bg_opa(ui.map_card, LV_OPA_COVER, 0);
        lv_obj_set_style_border_width(ui.map_card, MCARD_BORDER, 0);
        lv_obj_set_style_border_color(ui.map_card, UI_C_LINE, 0);
        ui_map_set_cover(ui.map, ui.map_card, 20);
        ui.map_icon = ui_maneuver_create(ui.map_card, CARD_FITS[0].icon);
        pair_create(&ui.map_dist, ui.map_card, CARD_FITS[0].num, UI_C_TEXT, CARD_FITS[0].unit, UI_C_TEXT2);
        ui.map_street = label(ui.map_card, &font_semi_22, UI_C_TEXT, "");

        /* Time left and arrival float beside the rider, only while stopped: a red light is when there
         * is time to read them. */
        ui.map_trip = label(ui.scr_map, &font_med_18, UI_C_TEXT2, "");
        lv_obj_set_style_text_align(ui.map_trip, LV_TEXT_ALIGN_RIGHT, 0);
        lv_obj_set_style_bg_color(ui.map_trip, UI_C_BG, 0);
        lv_obj_set_style_bg_opa(ui.map_trip, LV_OPA_COVER, 0);
        lv_obj_set_style_border_width(ui.map_trip, MCARD_BORDER, 0);
        lv_obj_set_style_border_color(ui.map_trip, UI_C_LINE, 0);
        lv_obj_set_style_radius(ui.map_trip, 12, 0);
        lv_obj_set_style_pad_hor(ui.map_trip, 10, 0);
        lv_obj_set_style_pad_ver(ui.map_trip, 5, 0);
        lv_obj_align(ui.map_trip, LV_ALIGN_BOTTOM_RIGHT, -MCARD_M, -(MCARD_M + MCARD_H + 8));
        lv_obj_set_hidden(ui.map_trip, true);
        return;
    }

    lv_obj_t *fade = box(ui.scr_map);
    lv_obj_set_style_bg_opa(fade, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(fade, UI_C_BG, 0);
    lv_obj_set_style_bg_grad_color(fade, UI_C_BG, 0);
    lv_obj_set_style_bg_grad_dir(fade, LV_GRAD_DIR_VER, 0);

    /* Round panel: full-bleed map, turn + distance low in the dial, progress on the rim. */
    lv_obj_set_pos(fade, 0, ui.h * 50 / 100);
    lv_obj_set_size(fade, ui.w, ui.h * 50 / 100);
    lv_obj_set_style_bg_main_opa(fade, LV_OPA_TRANSP, 0);
    lv_obj_set_style_bg_grad_opa(fade, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_main_stop(fade, 40, 0);

    ui.map_arc = lv_arc_create(ui.scr_map);
    lv_obj_remove_style_all(ui.map_arc);
    lv_obj_set_clickable(ui.map_arc, false);
    lv_obj_set_size(ui.map_arc, ui.w - 22, ui.h - 22);
    lv_obj_center(ui.map_arc);
    lv_arc_set_rotation(ui.map_arc, 0);
    lv_arc_set_bg_angles(ui.map_arc, 135, 45);
    lv_arc_set_range(ui.map_arc, 0, 1000);
    for (int part = 0; part < 2; part++) {
        lv_style_selector_t sel = part ? LV_PART_INDICATOR : LV_PART_MAIN;
        lv_obj_set_style_arc_width(ui.map_arc, 7, sel);
        lv_obj_set_style_arc_rounded(ui.map_arc, true, sel);
    }
    lv_obj_set_style_arc_color(ui.map_arc, UI_C_LINE, LV_PART_MAIN);

    ui.map_icon = ui_maneuver_create(ui.scr_map, 68);
    pair_create(&ui.map_dist, ui.scr_map, &font_num_64, UI_C_TEXT, &font_semi_26, UI_C_TEXT2);
    ui.map_street = label(ui.scr_map, &font_med_18, UI_C_TEXT2, "");
    lv_obj_set_style_text_align(ui.map_street, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_set_pos(ui.map_street, ui.w * 20 / 100, ui.h * 80 / 100 + 8);
}

/* ---------- updates ---------- */

static int icon_slot(uint32_t hash)
{
    if (!hash) return -1;
    for (int i = 0; i < ICON_SLOTS; i++)
        if (ui.icon_hash[i] == hash) return i;
    return -1;
}

/* Share of the current step ridden, 0..1000, or -1 when unknown. */
static int32_t step_done(const nav_state_t *n)
{
    if (n->dist_to_turn_m == NAV_U32_UNKNOWN || ui.step_start == 0) return -1;
    return LV_CLAMP(0, 1000 - (int32_t)((uint64_t)n->dist_to_turn_m * 1000 / ui.step_start), 1000);
}

static void update_nav(void)
{
    const nav_state_t *n = &ui.nav;
    int32_t cw = ui.w - 2 * CARD_X;
    bool rerouting = n->flags & NAV_F_REROUTING;
    nav_urgency_t u = nav_urgency(n);
    bool now = u == NAV_URGENCY_NOW;

    /* Far: green arrow on grey. Near: the arrow turns yellow. Now: the whole card lights up yellow. */
    lv_color_t card = now ? UI_C_NEAR : UI_C_CARD;
    lv_color_t ink = now ? UI_C_ON_NEAR : u == NAV_URGENCY_NEAR ? UI_C_NEAR : UI_C_ACCENT;
    lv_color_t txt = now ? UI_C_ON_NEAR : UI_C_TEXT;
    lv_color_t txt2 = now ? UI_C_ON_NEAR2 : UI_C_TEXT2;
    set_bg(ui.card, card, 0);
    if (!lv_color_eq(lv_obj_get_style_border_color(ui.card, 0), now ? UI_C_NEAR : UI_C_CARD_EDGE))
        lv_obj_set_style_border_color(ui.card, now ? UI_C_NEAR : UI_C_CARD_EDGE, 0);

    /* A new step starts when the maneuver changes, or the distance jumps back up (reroute). */
    uint32_t key = (n->maneuver * 2654435761u) ^ n->icon_hash ^ str_hash(n->primary);
    bool known = n->dist_to_turn_m != NAV_U32_UNKNOWN;
    if (key != ui.step_key) {
        ui.step_key = key;
        ui.step_start = known ? n->dist_to_turn_m : 0;
        icon_animate_in();
    } else if (known && n->dist_to_turn_m > ui.step_start) {
        ui.step_start = n->dist_to_turn_m;
    }

    int slot = icon_slot(n->icon_hash);
    spinner_set(rerouting && ui.anims);
    set_visible(ui.man_img, !rerouting && slot >= 0);
    set_visible(ui.man_vec, !rerouting && slot < 0);
    if (slot >= 0) {
        lv_image_set_src(ui.man_img, &ui.icon_dsc[slot]);
        if (!lv_color_eq(lv_obj_get_style_image_recolor(ui.man_img, 0), ink))
            lv_obj_set_style_image_recolor(ui.man_img, ink, 0);
    } else {
        ui_maneuver_set(ui.man_vec, n->maneuver, n->exit_num, ink, card);
    }

    char num[16], unit[8];
    int32_t done = step_done(n);
    set_visible(ui.reroute, rerouting);
    set_visible(ui.dist.num, !rerouting);
    set_visible(ui.dist.unit, !rerouting);
    set_visible(ui.bar, !rerouting && done >= 0);
    if (!rerouting) {
        nav_fmt_distance(n->dist_to_turn_m, ui.imperial, num, sizeof(num), unit, sizeof(unit));
        pair_color(&ui.dist, txt, txt2);
        pair_place(&ui.dist, num, unit, cw / 2, DIST_BASE, 6);
        if (done >= 0) {
            lv_bar_set_value(ui.bar, done, LV_ANIM_OFF);
            set_bg(ui.bar, now ? lv_color_mix(UI_C_ON_NEAR, UI_C_NEAR, LV_OPA_30) : UI_C_LINE, LV_PART_MAIN);
            set_bg(ui.bar, ink, LV_PART_INDICATOR);
        }
    }

    /* Street, never cut: shrinks, abbreviates, then wraps. "toward ..." only fits under a one-line name. */
    const char *pri = rerouting ? "Finding a new route" : n->primary[0] ? n->primary : n->secondary;
    const char *sec = rerouting || !n->primary[0] ? "" : n->secondary;
    int32_t sw = ui.w - 28, room = TRIP_LINE_Y - 1 - STREET_Y;
    int32_t ph = fit_text(ui.primary, pri, FITS(FIT_PRIMARY), sw, room, NULL);
    bool one = ph <= font_semi_26.line_height, whole = true;
    set_visible(ui.secondary, one && sec[0]);
    if (one && sec[0]) {
        fit_text(ui.secondary, sec, FITS(FIT_SECOND), sw, room - ph - 1, &whole);
        if (!whole && ph > font_semi_22.line_height) {
            /* A long "toward ..." gets two lines under the name one size down (it fit larger, so it fits). */
            ph = fit_text(ui.primary, pri, FIT_PRIMARY + 1, 1, sw, room, NULL);
            fit_text(ui.secondary, sec, FITS(FIT_SECOND), sw, room - ph - 1, NULL);
        }
        lv_obj_set_pos(ui.secondary, 14, STREET_Y + ph + 1);
    }

    /* Trip strip: ETA · time left · distance left. */
    int32_t col = ui.w / 3;
    if (n->eta_min_of_day != NAV_U16_UNKNOWN)
        nav_fmt_clock(n->eta_min_of_day / 60 % 24, n->eta_min_of_day % 60, ui.use_24h, num, sizeof(num));
    else snprintf(num, sizeof(num), "--:--");
    pair_place(&ui.trip[0], num, "ETA", col / 2, TRIP_BASE, 5);
    nav_fmt_duration(n->time_left_s, num, sizeof(num), unit, sizeof(unit));
    upper(unit);
    pair_place(&ui.trip[1], num, unit, col + col / 2, TRIP_BASE, 5);
    nav_fmt_distance(n->dist_left_m, ui.imperial, num, sizeof(num), unit, sizeof(unit));
    upper(unit);
    pair_place(&ui.trip[2], num, unit, 2 * col + col / 2, TRIP_BASE, 5);
}

/* Rider sits low on the rectangle, just above the card, to show the road ahead; centred on the dial. */
static void apply_view(void)
{
    if (ui.pose.m_per_px <= 0) return;
    map_view_t v = ui.pose;
    v.rider_y = ui.round ? ui.h / 2 : ui.h - MCARD_M - MCARD_H - RIDER_ABOVE_CARD;
    ui_map_set_view(ui.map, &v);
}

/* How far the map's farthest pixel moves between two views. */
static float view_shift_px(const map_view_t *a, const map_view_t *b)
{
    float dh = fabsf(fmodf(b->heading - a->heading + 540.f, 360.f) - 180.f) * (float)M_PI / 180.f;
    return (hypotf(b->x - a->x, b->y - a->y) + 200.f * fabsf(b->m_per_px - a->m_per_px)) / a->m_per_px + 200.f * dh;
}

/*
 * Poses come about once a second. Between two, the map glides from where it is drawn to the new one
 * over the time the last gap took, so it moves and turns continuously instead of stepping. A whole-map
 * frame costs about 100 ms on the chip, so mid-glide frames wait for a couple of pixels of change: a
 * turn gets every frame it can, a straight road a few a second.
 */
static void pose_tick(lv_timer_t *t)
{
    uint32_t el = lv_tick_elaps(ui.pose_t0);
    const map_view_t *a = &ui.pose_from, *b = &ui.pose_to;
    if (el >= ui.pose_ms) {
        ui.pose = *b;
        lv_timer_pause(t);
    } else {
        if (ui.calm && lv_tick_elaps(ui.pose_drawn_at) < CALM_FRAME_MS) return;
        float k = (float)el / (float)ui.pose_ms;
        float dh = fmodf(b->heading - a->heading + 540.f, 360.f) - 180.f;   /* the short way round */
        ui.pose.x = a->x + (b->x - a->x) * k;
        ui.pose.y = a->y + (b->y - a->y) * k;
        ui.pose.heading = fmodf(a->heading + dh * k + 360.f, 360.f);
        ui.pose.m_per_px = a->m_per_px + (b->m_per_px - a->m_per_px) * k;
        if (view_shift_px(&ui.pose_drawn, &ui.pose) < (ui.calm ? CALM_STEP_PX : GLIDE_STEP_PX)) return;
    }
    ui.pose_drawn = ui.pose;
    ui.pose_drawn_at = lv_tick_get();
    apply_view();
}

static int32_t font_w(const lv_font_t *f, const char *t)
{
    lv_point_t sz;
    lv_text_get_size(&sz, t, f, 0, 0, LV_COORD_MAX, LV_TEXT_FLAG_NONE);
    return sz.x;
}

/* Text width beside the arrow, or under it for a wide layout. */
static int32_t card_name_w(const card_fit_t *c)
{
    int32_t in = ui.w - 2 * MCARD_M - 2 * MCARD_BORDER;
    return c->wide ? in - 2 * MCARD_PAD : in - 2 * MCARD_PAD - c->icon - 10;
}

/* The largest layout that shows both the distance and the name whole. */
static const card_fit_t *card_fit(const char *name, const char *num, const char *unit)
{
    static uint32_t key;
    static int first = -1;      /* first layout the name fits, for the name last measured */
    uint32_t k = str_hash(name);
    if (first < 0 || k != key) {
        char ab[NAV_SECONDARY_MAX + 1];
        abbreviate(ab, sizeof(ab), name);
        first = CARD_FIT_N - 1;
        for (int i = 0; i < CARD_FIT_N && first == CARD_FIT_N - 1; i++) {
            const card_fit_t *c = &CARD_FITS[i];
            for (int j = 0; j < c->nname; j++)
                if (fits(name, &c->name[j], card_name_w(c)) || fits(ab, &c->name[j], card_name_w(c))) {
                    first = i;
                    break;
                }
        }
        key = k;
    }
    for (int i = first; i < CARD_FIT_N; i++) {
        const card_fit_t *c = &CARD_FITS[i];
        int32_t dw = font_w(c->num, num) + 4 + font_w(c->unit, unit);
        if (c->wide || dw <= card_name_w(c)) return c;
    }
    return &CARD_FITS[CARD_FIT_N - 1];
}

static void update_map(void)
{
    if (!ui.has_map) return;
    const nav_state_t *n = &ui.nav;
    nav_urgency_t u = nav_urgency(n);
    bool now = u == NAV_URGENCY_NOW;
    lv_color_t ink = u == NAV_URGENCY_FAR ? UI_C_ACCENT : UI_C_NEAR;
    ui_map_set_route_color(ui.map, ink);
    const char *street = n->primary[0] ? n->primary : n->secondary;
    int32_t done = step_done(n);

    char num[16], unit[8];
    nav_fmt_distance(n->dist_to_turn_m, ui.imperial, num, sizeof(num), unit, sizeof(unit));
    if (!ui.round) {
        lv_color_t bg = now ? UI_C_NEAR : UI_C_BG;
        lv_color_t txt = now ? UI_C_ON_NEAR : UI_C_TEXT, txt2 = now ? UI_C_ON_NEAR2 : UI_C_TEXT2;
        set_bg(ui.map_card, bg, 0);
        lv_color_t edge = now ? UI_C_NEAR : UI_C_LINE;
        if (!lv_color_eq(lv_obj_get_style_border_color(ui.map_card, 0), edge))
            lv_obj_set_style_border_color(ui.map_card, edge, 0);
        ui_maneuver_set(ui.map_icon, n->maneuver, n->exit_num, now ? UI_C_ON_NEAR : ink, bg);
        pair_color(&ui.map_dist, txt, txt2);
        set_ink(ui.map_street, txt);

        const card_fit_t *c = card_fit(street, num, unit);
        if (lv_obj_get_style_text_font(ui.map_dist.num, 0) != c->num) lv_obj_set_style_text_font(ui.map_dist.num, c->num, 0);
        if (lv_obj_get_style_text_font(ui.map_dist.unit, 0) != c->unit) lv_obj_set_style_text_font(ui.map_dist.unit, c->unit, 0);
        lv_obj_set_size(ui.map_icon, c->icon, c->icon);
        int32_t nw = card_name_w(c);
        int32_t sh = fit_text(ui.map_street, street, c->name, c->nname, nw, LV_COORD_MAX, NULL);

        /* Arrow, distance and name as one block, centred in the card. */
        int32_t in_h = MCARD_H - 2 * MCARD_BORDER, asc = c->num->line_height - c->num->base_line;
        int32_t dw = pair_width(&ui.map_dist, num, unit, 4), dx, base, ny;
        if (c->wide) {
            int32_t top = (in_h - (c->icon + 4 + sh)) / 2;
            lv_obj_set_pos(ui.map_icon, MCARD_PAD, top);
            dx = MCARD_PAD + c->icon + 8;
            base = top + c->icon / 2 + asc / 2;
            ny = top + c->icon + 4;
            lv_obj_set_pos(ui.map_street, MCARD_PAD, ny);
        } else {
            int32_t top = (in_h - (asc + 3 + sh)) / 2;
            lv_obj_set_pos(ui.map_icon, MCARD_PAD, (in_h - c->icon) / 2);
            dx = MCARD_PAD + c->icon + 10;
            base = top + asc;
            ny = base + 3;
            lv_obj_set_pos(ui.map_street, dx, ny);
        }
        pair_place(&ui.map_dist, num, unit, dx + dw / 2, base, 4);

        char trip[24] = "";
        bool stopped = n->speed_kmh_x10 != NAV_U16_UNKNOWN && n->speed_kmh_x10 < STOPPED_KMH_X10;
        if (stopped && !now && n->time_left_s != NAV_U32_UNKNOWN) {
            char tn[12], tu[8], eta[12] = "";
            nav_fmt_duration(n->time_left_s, tn, sizeof(tn), tu, sizeof(tu));
            if (n->eta_min_of_day < 24 * 60)
                nav_fmt_clock(n->eta_min_of_day / 60, n->eta_min_of_day % 60, ui.use_24h, eta, sizeof(eta));
            snprintf(trip, sizeof(trip), eta[0] ? "%s %s\n%s" : "%s %s", tn, tu, eta);
        }
        if (trip[0]) set_text(ui.map_trip, trip);
        if (!trip[0] != lv_obj_is_hidden(ui.map_trip)) lv_obj_set_hidden(ui.map_trip, !trip[0]);
        return;
    }
    ui_maneuver_set(ui.map_icon, n->maneuver, n->exit_num, ink, UI_C_BG);
    fit_text(ui.map_street, street, FITS(FIT_LINE), ui.w * 60 / 100, LV_COORD_MAX, NULL);
    int32_t gap = 12, icon = lv_obj_get_width(ui.map_icon), base = ui.h * 80 / 100;
    int32_t wd = pair_width(&ui.map_dist, num, unit, 5);
    int32_t x = ui.w / 2 - (icon + gap + wd) / 2;
    lv_obj_set_pos(ui.map_icon, x, base - 24 - icon / 2);
    pair_place(&ui.map_dist, num, unit, x + icon + gap + wd / 2, base, 5);
    lv_arc_set_value(ui.map_arc, LV_MAX(done, 0));
    if (!lv_color_eq(lv_obj_get_style_arc_color(ui.map_arc, LV_PART_INDICATOR), ink))
        lv_obj_set_style_arc_color(ui.map_arc, ink, LV_PART_INDICATOR);
}

static void update_idle(void)
{
    static const char *const days[] = {"SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"};
    static const char *const months[] = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                                         "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    char buf[40];
    if (ui.hour >= 0 && ui.wday >= 0 && ui.wday < 7 && ui.month >= 0 && ui.month < 12)
        snprintf(buf, sizeof(buf), "%s %d %s", days[ui.wday], ui.mday, months[ui.month]);
    else buf[0] = '\0';
    set_text(ui.idle_date, buf);

    nav_fmt_clock(ui.hour, ui.minute, ui.use_24h, buf, sizeof(buf));
    pair_place(&ui.idle_clock, buf, "", ui.w / 2, 132, 0);

    int spd = nav_speed_display(ui.nav.speed_kmh_x10, ui.imperial);
    if (spd >= 0) snprintf(buf, sizeof(buf), "%d", spd);
    else snprintf(buf, sizeof(buf), "--");
    pair_place(&ui.idle_speed, buf, ui.imperial ? "mph" : "km/h", (ui.w - 32) / 2, 76, 6);
}

static void update_arrive(void)
{
    fit_text(ui.arr_dest, ui.nav.primary[0] ? ui.nav.primary : ui.nav.secondary, FITS(FIT_DEST), ui.w - 40, LV_COORD_MAX, NULL);
}

static void show(lv_obj_t *scr)
{
    if (ui.cur != scr) {
        ui.cur = scr;
        if (ui.anims) lv_screen_load_anim(scr, LV_SCREEN_LOAD_ANIM_FADE_IN, 220, 0, false);
        else lv_screen_load(scr);
    }
    rings_run(ui.anims && scr == ui.scr_wait);
    update_statusbar();
}

static void refresh(void)
{
    lv_obj_t *target = ui.sleeping                           ? ui.scr_sleep
                       : !ui.connected                       ? ui.scr_wait
                       : (ui.nav.flags & NAV_F_ARRIVED)      ? ui.scr_arrive
                       : (ui.nav.flags & NAV_F_NAVIGATING) && ui.has_map && ui.has_pose && !ui.prefer_cards &&
                         !(ui.nav.flags & NAV_F_REROUTING)   ? ui.scr_map
                       : (ui.nav.flags & NAV_F_NAVIGATING)   ? ui.scr_nav
                                                             : ui.scr_idle;
    show(target);
}

/* ---------- public API ---------- */

void ui_init(void)
{
    memset(&ui, 0, sizeof(ui));
    ui.w = lv_display_get_horizontal_resolution(lv_display_get_default());
    ui.h = lv_display_get_vertical_resolution(lv_display_get_default());
    ui.round = ui.w == ui.h;
    ui.anims = true;
    ui.batt_pct = -1;
    ui.hour = -1;
    nav_state_clear(&ui.nav);

    build_statusbar();
    build_wait();
    build_idle();
    build_nav();
    build_arrive();
    build_sleep();
    build_map();
    build_toast();

    update_nav();
    update_idle();
    ui.cur = ui.scr_wait;
    lv_screen_load(ui.scr_wait);
    refresh();
}

void ui_set_link(bool connected)
{
    ui.connected = connected;
    if (connected) ui.sleeping = false;
    else nav_state_clear(&ui.nav);
    refresh();
}

void ui_set_nav(const nav_state_t *nav)
{
    ui.nav = *nav;
    update_nav();
    update_map();
    update_idle();
    update_arrive();
    refresh();
}

void ui_set_map(const map_data_t *data)
{
    ui.has_map = data && data->nlines + data->nroute > 0;
    if (ui.has_map) ui_map_set_data(ui.map, data);
    else ui.has_pose = false, ui.pose.m_per_px = 0;
    ui.pose_cut = true;   /* the next pose is in the new map's frame */
    if (ui.pose_timer) lv_timer_pause(ui.pose_timer);
    update_map();
    refresh();
}

void ui_set_map_pose(float x, float y, float heading, float m_per_px)
{
    map_view_t to = {.x = x, .y = y, .heading = heading, .m_per_px = m_per_px};
    uint32_t gap = lv_tick_elaps(ui.pose_rx);
    ui.pose_rx = lv_tick_get();
    float jump = ui.pose.m_per_px > 0 ? hypotf(x - ui.pose.x, y - ui.pose.y) / m_per_px : 0;
    if (ui.pose_cut || ui.pose.m_per_px <= 0 || !ui.anims || jump > POSE_JUMP_PX ||
        m_per_px > 2 * ui.pose.m_per_px || 2 * m_per_px < ui.pose.m_per_px) {
        /* A new map (its own frame), the first pose, or a leap: no glide that would mean anything. */
        ui.pose_cut = false;
        ui.pose = ui.pose_to = ui.pose_drawn = to;
        ui.pose_drawn_at = ui.pose_rx;
        if (ui.pose_timer) lv_timer_pause(ui.pose_timer);
        apply_view();
    } else {
        ui.pose_from = ui.pose;
        ui.pose_to = to;
        ui.pose_t0 = ui.pose_rx;
        ui.pose_ms = LV_CLAMP(200, gap, POSE_MAX_MS);
        if (!ui.pose_timer) ui.pose_timer = lv_timer_create(pose_tick, 30, NULL);
        lv_timer_resume(ui.pose_timer);
    }
    if (!ui.has_pose) {
        ui.has_pose = true;
        refresh();
    }
}

bool ui_toggle_view(void)
{
    if (!(ui.nav.flags & NAV_F_NAVIGATING) || !ui.has_map) return false;
    ui.prefer_cards = !ui.prefer_cards;
    refresh();
    return true;
}

bool ui_has_icon(uint32_t hash)
{
    return icon_slot(hash) >= 0;
}

void ui_set_icon(uint32_t hash, const uint8_t *a8)
{
    if (!hash) return;
    int slot = icon_slot(hash);
    if (slot < 0) {
        slot = ui.icon_next;
        if (ui.icon_hash[slot] == ui.nav.icon_hash) slot = (slot + 1) % ICON_SLOTS;
        ui.icon_next = (uint8_t)((slot + 1) % ICON_SLOTS);
    }
    memcpy(icon_buf[slot], a8, NAV_ICON_BYTES);
    ui.icon_hash[slot] = hash;
    lv_image_dsc_t *d = &ui.icon_dsc[slot];
    memset(d, 0, sizeof(*d));
    d->header.magic = LV_IMAGE_HEADER_MAGIC;
    d->header.cf = LV_COLOR_FORMAT_A8;
    d->header.w = NAV_ICON_PX;
    d->header.h = NAV_ICON_PX;
    d->header.stride = NAV_ICON_PX;
    d->data_size = NAV_ICON_BYTES;
    d->data = icon_buf[slot];
    if (hash == ui.nav.icon_hash) {
        lv_image_set_src(ui.man_img, NULL);
        update_nav();
    }
}

void ui_set_battery(int pct, bool charging)
{
    ui.batt_pct = pct;
    ui.charging = charging;
    update_statusbar();
}

void ui_set_clock(int hour, int minute, int wday, int mday, int month)
{
    ui.hour = hour;
    ui.minute = minute;
    ui.wday = wday;
    ui.mday = mday;
    ui.month = month;
    update_statusbar();
    update_idle();
}

void ui_set_prefs(bool imperial, bool use_24h)
{
    ui.imperial = imperial;
    ui.use_24h = use_24h;
    update_nav();
    update_idle();
    update_statusbar();
}

static void toast_hide(lv_timer_t *t)
{
    (void)t;
    lv_obj_set_hidden(ui.toast, true);
    ui.toast_timer = NULL;
}

void ui_show_toast(const char *text, uint32_t ms)
{
    set_text(ui.toast_lbl, text);
    lv_obj_set_hidden(ui.toast, false);
    if (ui.toast_timer) lv_timer_delete(ui.toast_timer);
    ui.toast_timer = lv_timer_create(toast_hide, ms, NULL);
    lv_timer_set_repeat_count(ui.toast_timer, 1);
}

void ui_show_sleep(void)
{
    ui.sleeping = true;
    lv_obj_set_hidden(ui.toast, true);
    refresh();
}

void ui_set_calm(bool calm)
{
    ui.calm = calm;
}

void ui_set_animations(bool enable)
{
    if (ui.anims == enable) return;
    ui.anims = enable;
    rings_run(enable && ui.cur == ui.scr_wait);
    if (!enable) icon_in_exec(ui.icon_box, 256);
    update_nav();
}
