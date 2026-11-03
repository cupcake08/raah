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
