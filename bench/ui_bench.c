#include "ui_bench.h"

#include <stdio.h>
#include <string.h>

#include "demo_map.h"
#include "nav_model.h"
#include "ui.h"

enum { FRAMES = 60 };

static const ui_bench_env_t *env;

typedef struct {
    uint64_t px, flushes;
} mark_t;

static uint64_t t0, busy;

static mark_t mark(void)
{
    memset(&ui_map_stats, 0, sizeof(ui_map_stats));
    busy = 0;
    return (mark_t){*env->px, *env->flushes};
}

/* A frame is timed from its first UI call to the end of its refresh. */
static void begin(void)
{
    t0 = env->now();
}

static void end(void)
{
    lv_refr_now(NULL);
    busy += env->now() - t0;
    if (env->yield) env->yield();
}

static void report(const char *name, mark_t a)
{
    double f = FRAMES, us = (double)busy / env->per_us / f;
    printf("%-36s %7.0f %6.1f %7.0f %6.0f %7.0f %9.1f\n", name, (double)(*env->px - a.px) / f,
           (double)(*env->flushes - a.flushes) / f, ui_map_stats.transforms / f, ui_map_stats.polylines / f,
           ui_map_stats.segments / f, us);
}

void ui_bench_run(const ui_bench_env_t *e)
{
    static const map_data_t data = {
        .pts = demo_pts,
        .lines = demo_lines,
        .nlines = sizeof(demo_lines) / sizeof(demo_lines[0]),
        .route = demo_route,
        .nroute = sizeof(demo_route) / sizeof(demo_route[0]),
    };
    env = e;
    nav_state_t n;
    nav_state_clear(&n);
    n.flags = NAV_F_NAVIGATING;
    n.maneuver = MANEUVER_RIGHT;
    n.dist_to_turn_m = 900;
    n.dist_left_m = 6400;
    n.time_left_s = 18 * 60;
    n.eta_min_of_day = 22 * 60 + 52;
    n.speed_kmh_x10 = 243;
    strcpy(n.primary, "Chinmaya Mission Hospital Rd");
    /* One frame per pose: the cost of a whole map redraw, without the glide's in-between frames. */
    ui_set_animations(false);
    ui_set_map(&data);
    ui_set_map_pose(-189, 0, -3.0f, 1.5f);
    ui_set_nav(&n);
    env->run(800);

    printf("%-36s %7s %6s %7s %6s %7s %9s\n", "per frame", "pixels", "strips", "xforms", "lines", "segs", "us");

    /* 1. Riding at 22 km/h: one GPS fix and one STATE per second, each moving the map. */
    mark_t m = mark();
    for (int i = 0; i < FRAMES; i++) {
        begin();
        n.dist_to_turn_m -= 6;
        ui_set_nav(&n);
        ui_set_map_pose(-189, 6.0f * i, -3.0f, 1.5f);
        end();
    }
    report("riding: pose + STATE every second", m);

    /* 2. Stopped at a signal: a few decimetres of GPS jitter, the same STATE resent. The heading comes
     *    from the route bearing while on route, so it holds still. */
    m = mark();
    for (int i = 0; i < FRAMES; i++) {
        begin();
        ui_set_nav(&n);
        ui_set_map_pose(-189 + 0.3f * ((i * 7) % 3 - 1), 354 + 0.3f * ((i * 5) % 3 - 1), -3.0f, 1.5f);
        end();
    }
    report("stopped: GPS jitter, same STATE", m);

    /* 3. Only the distance changes, the map holds still. */
    m = mark();
    for (int i = 0; i < FRAMES; i++) {
        begin();
        n.dist_to_turn_m = 500 - 10 * (i % 2);
        ui_set_nav(&n);
        end();
    }
    report("distance digits change only", m);
}

/* ---------- overlay parts ---------- */

static double area_us(const lv_area_t *a)
{
    enum { N = 30 };
    lv_refr_now(NULL);      /* what the last show/hide invalidated is not part of this area's cost */
    busy = 0;
    for (int i = 0; i < N; i++) {
        begin();
        lv_obj_invalidate_area(lv_screen_active(), a);
        end();
    }
    return (double)busy / env->per_us / N;
}

static void part(const char *name, const lv_area_t *a, int n)
{
    double us = 0;
    for (int i = 0; i < n; i++) us += area_us(&a[i]);
    printf("  %-40s %9.1f\n", name, us);
}

static void hide(lv_obj_t *o, bool h)
{
    lv_obj_set_hidden(o, h);
}

/* Per object: time from its draw starting to its children finishing, summed over a frame's strips. */
typedef struct {
    lv_obj_t *obj;
    const char *name;
    uint64_t t0, sum;
    bool tree;      /* timed to its children's end, else to its own drawing's */
} timed_t;

static timed_t timed[16];
static int ntimed;

static void time_cb(lv_event_t *e)
{
    timed_t *t = lv_event_get_user_data(e);
    if (lv_event_get_code(e) == LV_EVENT_DRAW_MAIN_BEGIN) t->t0 = env->now();
    else t->sum += env->now() - t->t0;
}

static void watch(lv_obj_t *o, const char *name)
{
    if (!o || ntimed == (int)(sizeof(timed) / sizeof(timed[0]))) return;
    timed_t *t = &timed[ntimed++];
    *t = (timed_t){.obj = o, .name = name, .tree = lv_obj_get_child_count(o) > 0};
    lv_obj_add_event_cb(o, time_cb, LV_EVENT_DRAW_MAIN_BEGIN, t);
    lv_obj_add_event_cb(o, time_cb, t->tree ? LV_EVENT_DRAW_POST_END : LV_EVENT_DRAW_MAIN_END, t);
}

void ui_bench_parts(const ui_bench_env_t *e)
{
    env = e;
    /* The map screen as ui.c builds it: map, status bar band, top fade, card (icon, distance, unit,
     * street), trip label; the status bar is on the top layer. */
    lv_obj_t *scr = lv_screen_active(), *sb = lv_obj_get_child(lv_layer_top(), 0);
    if (lv_obj_get_child_count(scr) < 4 || !sb) return;
    lv_obj_t *map = lv_obj_get_child(scr, 0), *fade = lv_obj_get_child(scr, 2), *card = lv_obj_get_child(scr, 3);
    lv_area_t c, s;
    lv_obj_get_coords(card, &c);
    lv_obj_get_coords(scr, &s);
    lv_area_t top = {s.x1, s.y1, s.x2, s.y1 + 45}, mid = {s.x1, s.y1 + 100, s.x2, s.y1 + 145};
    /* Beside the card, as far in as its rounded corners, where the map shows through. */
    lv_area_t sides[2] = {{s.x1, c.y1, c.x1 + 19, c.y2}, {c.x2 - 19, c.y1, s.x2, c.y2}};
    lv_area_t margins[2] = {{s.x1, c.y1, c.x1 - 1, c.y2}, {c.x2 + 1, c.y1, s.x2, c.y2}};

    /* Who costs what in a riding frame (the map's own time includes nothing drawn over it). */
    static const char *const sb_names[] = {"status bar: clock", "status bar: speed", "status bar: speed unit",
                                           "status bar: battery", "status bar: battery %"};
    static const char *const card_names[] = {"card: arrow", "card: distance", "card: distance unit", "card: street"};
    ntimed = 0;
    watch(map, "map");
    watch(fade, "top fade");
    watch(sb, "status bar, all");
    for (uint32_t i = 0; i < lv_obj_get_child_count(sb) && i < 5; i++) watch(lv_obj_get_child(sb, i), sb_names[i]);
    watch(card, "card, all");
    for (uint32_t i = 0; i < lv_obj_get_child_count(card) && i < 4; i++) watch(lv_obj_get_child(card, i), card_names[i]);
    enum { N = 20 };
    lv_refr_now(NULL);
    busy = 0;
    for (int i = 0; i < N; i++) {
        begin();
        ui_set_map_pose(-189, 6.0f * (i % 2), -3.0f, 1.5f);   /* the riding scenario's move: the map, less the card */
        end();
    }
    double frame = (double)busy;
    printf("\n%-42s %9s\n", "object, riding frame", "us");
    printf("  %-40s %9.1f\n", "frame", frame / env->per_us / N);
    for (int i = 0; i < ntimed; i++) {
        printf("  %-40s %9.1f\n", timed[i].name, (double)timed[i].sum / env->per_us / N);
        lv_obj_remove_event_cb_with_user_data(timed[i].obj, time_cb, &timed[i]);
    }

    printf("\n%-42s %9s\n", "overlay part, area re-rendered", "us");
    part("top 46 rows: all", &top, 1);
    part("top 46 rows: map only (mid-screen rows)", &mid, 1);
    hide(fade, true);
    part("top 46 rows: no fade", &top, 1);
    hide(sb, true);
    part("top 46 rows: no fade, no status bar", &top, 1);
    hide(fade, false);
    part("top 46 rows: no status bar", &top, 1);
    hide(sb, false);
    hide(map, true);
    part("top 46 rows: no map", &top, 1);
    hide(map, false);

    part("card sides: all", sides, 2);
    part("card sides: outer margins only", margins, 2);
    for (uint32_t i = 0; i < lv_obj_get_child_count(card); i++) {
        char name[48];
        snprintf(name, sizeof(name), "card sides: card child %u hidden", (unsigned)i);
        hide(lv_obj_get_child(card, i), true);
        part(name, sides, 2);
        hide(lv_obj_get_child(card, i), false);
    }
    int32_t r = lv_obj_get_style_radius(card, 0);
    lv_obj_set_style_radius(card, 0, 0);
    part("card sides: square card", sides, 2);
    lv_obj_set_style_radius(card, r, 0);
    hide(card, true);
    part("card sides: no card", sides, 2);
    hide(card, false);
    hide(map, true);
    part("card sides: no map", sides, 2);
    hide(map, false);
}
