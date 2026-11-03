/*
 * Renders the Raah UI for a set of ride scenarios into BMP files
 * (one per screen plus a contact sheet).
 * Usage: raah_sim <out_dir> [round]  |  raah_sim <out_dir> replay <capture.bin>  |  raah_sim - bench
 */
#include <math.h>
#include <stdint.h>
#include <time.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lvgl.h"
#include "ui.h"
#include "ui_draw.h"
#include "ui_theme.h"
#include "demo_map.h"
#include "map_proto.h"
#include "ui_bench.h"

#define MAX_PX (412 * 412)
#define COLS 5
#define GAP 28

static int W = 240, H = 320;
static bool ROUND;
static uint16_t fb[MAX_PX] __attribute__((aligned(64)));
static uint32_t fake_ms;
static const char *out_dir;

typedef struct {
    const char *name;
    uint8_t *rgb;
} shot_t;

static shot_t shots[16];
static int shot_count;

static uint32_t tick(void) { return fake_ms; }

/* Same strip height as firmware/main/display.c, so the work per frame matches the device. */
#define DRAW_LINES 20
static uint16_t strip[412 * DRAW_LINES];
static uint64_t px_flushed, flushes;

static void flush(lv_display_t *d, const lv_area_t *a, uint8_t *px)
{
    int32_t w = lv_area_get_width(a);
    for (int32_t y = a->y1; y <= a->y2; y++)
        memcpy(&fb[y * W + a->x1], (uint16_t *)px + (y - a->y1) * w, (size_t)w * 2);
    px_flushed += (uint64_t)w * lv_area_get_height(a);
    flushes++;
    lv_display_flush_ready(d);
}

static void run(uint32_t ms)
{
    for (uint32_t t = 0; t < ms; t += 5) {
        fake_ms += 5;
        lv_timer_handler();
    }
}

static void write_bmp(const char *path, const uint8_t *rgb, int w, int h)
{
    FILE *f = fopen(path, "wb");
    if (!f) {
        perror(path);
        exit(1);
    }
    int row = (w * 3 + 3) & ~3;
    uint32_t size = 54 + (uint32_t)row * h;
    uint8_t hdr[54] = {'B', 'M'};
    memcpy(hdr + 2, &size, 4);
    hdr[10] = 54;
    hdr[14] = 40;
    memcpy(hdr + 18, &w, 4);
    memcpy(hdr + 22, &h, 4);
    hdr[26] = 1;
    hdr[28] = 24;
    fwrite(hdr, 1, 54, f);
    uint8_t *line = calloc(1, row);
    for (int y = h - 1; y >= 0; y--) {
        for (int x = 0; x < w; x++) {
            const uint8_t *p = rgb + (y * w + x) * 3;
            line[x * 3] = p[2];
            line[x * 3 + 1] = p[1];
            line[x * 3 + 2] = p[0];
        }
        fwrite(line, 1, row, f);
    }
    free(line);
    fclose(f);
}

static void snap(const char *name)
{
    lv_refr_now(NULL);
    if (shot_count == (int)(sizeof(shots) / sizeof(shots[0]))) {
        fprintf(stderr, "too many screenshots, skipping %s\n", name);
        return;
    }
    shot_t *s = &shots[shot_count++];
    s->name = name;
    s->rgb = malloc((size_t)W * H * 3);
    for (int i = 0; i < W * H; i++) {
        uint16_t c = fb[i];
        s->rgb[i * 3] = (uint8_t)(((c >> 11) & 0x1F) * 255 / 31);
        s->rgb[i * 3 + 1] = (uint8_t)(((c >> 5) & 0x3F) * 255 / 63);
        s->rgb[i * 3 + 2] = (uint8_t)((c & 0x1F) * 255 / 31);
        if (ROUND) { /* outside the glass of a round panel */
            int x = i % W - W / 2, y = i / W - H / 2;
            if (x * x + y * y > (W / 2) * (W / 2)) s->rgb[i * 3] = s->rgb[i * 3 + 1] = s->rgb[i * 3 + 2] = 0x22;
        }
    }
    char path[512];
    snprintf(path, sizeof(path), "%s/%s.bmp", out_dir, name);
    write_bmp(path, s->rgb, W, H);
}

static void report_mem(void)
{
    lv_mem_monitor_t m;
    lv_mem_monitor(&m);
    printf("LVGL heap peak %u KB of %u KB (64-bit host; a 32-bit target needs less)\n",
           (unsigned)(m.max_used / 1024), (unsigned)(m.total_size / 1024));
}

static void contact_sheet(void)
{
    report_mem();
    int rows = (shot_count + COLS - 1) / COLS;
    int sw = COLS * W + (COLS + 1) * GAP, sh = rows * H + (rows + 1) * GAP;
    uint8_t *img = malloc((size_t)sw * sh * 3);
    for (int i = 0; i < sw * sh; i++) {
        img[i * 3] = 0x22;
        img[i * 3 + 1] = 0x25;
        img[i * 3 + 2] = 0x2B;
    }
    for (int k = 0; k < shot_count; k++) {
        int ox = GAP + (k % COLS) * (W + GAP), oy = GAP + (k / COLS) * (H + GAP);
        for (int y = 0; y < H; y++) memcpy(img + ((size_t)(oy + y) * sw + ox) * 3, shots[k].rgb + y * W * 3, W * 3);
    }
    char path[512];
    snprintf(path, sizeof(path), "%s/contact_sheet.bmp", out_dir);
    write_bmp(path, img, sw, sh);
    free(img);
}

/* A Google-Maps-like roundabout glyph, rasterised with 4x4 supersampling, to exercise the bitmap path. */
static void synth_icon(uint8_t *a8)
{
    const float n = NAV_ICON_PX;
    for (int y = 0; y < NAV_ICON_PX; y++)
        for (int x = 0; x < NAV_ICON_PX; x++) {
            int hits = 0;
            for (int sy = 0; sy < 4; sy++)
                for (int sx = 0; sx < 4; sx++) {
                    float u = (x + (sx + .5f) / 4) / n * 100, v = (y + (sy + .5f) / 4) / n * 100;
                    float r = hypotf(u - 50, v - 58);
                    bool ring = r > 13 && r < 22 && !(u > 50 && v < 58);          /* 3/4 ring, open top-right */
                    bool stem = fabsf(u - 50) < 6 && v > 76 && v < 96;              /* entry */
                    bool exit_bar = v > 34 && v < 46 && u > 60 && u < 80 && v < 104 - u;
                    bool exit_head = u >= 68 && u < 92 && fabsf(v - 26) < (92 - u) * 0.9f; /* arrow to the right-up */
                    hits += ring || stem || exit_bar || exit_head;
                }
            a8[y * NAV_ICON_PX + x] = (uint8_t)(hits * 255 / 16);
        }
}

static nav_state_t base_nav(void)
{
    nav_state_t n;
    nav_state_clear(&n);
    n.flags = NAV_F_NAVIGATING;
    n.dist_left_m = 6400;
    n.time_left_s = 18 * 60;
    n.eta_min_of_day = 22 * 60 + 52;
    n.speed_kmh_x10 = 243;
    return n;
}

static void icon_sheet(void)
{
    static const char *names[] = {"unknown", "straight", "slight L", "slight R", "left", "right", "sharp L", "sharp R",
                                  "u-turn L", "u-turn R", "fork L", "fork R", "merge", "round 2", "arrive"};
    lv_obj_t *scr = lv_obj_create(NULL);
    lv_obj_remove_style_all(scr);
    lv_obj_set_style_bg_color(scr, UI_C_BG, 0);
    lv_obj_set_style_bg_opa(scr, LV_OPA_COVER, 0);
    for (int i = 1; i < MANEUVER_COUNT; i++) {
        int k = i - 1, cx = 12 + (k % 4) * 56, cy = 36 + (k / 4) * 72;
        lv_obj_t *m = ui_maneuver_create(scr, 48);
        lv_obj_set_pos(m, cx + 4, cy);
        ui_maneuver_set(m, (uint8_t)i, i == MANEUVER_ROUNDABOUT ? 2 : 0, i == MANEUVER_ARRIVE ? UI_C_ACCENT : UI_C_TEXT,
                        UI_C_BG);
        lv_obj_t *l = lv_label_create(scr);
        lv_obj_set_style_text_font(l, &font_label_12, 0);
        lv_obj_set_style_text_color(l, UI_C_TEXT3, 0);
        lv_obj_set_width(l, 56);
        lv_obj_set_style_text_align(l, LV_TEXT_ALIGN_CENTER, 0);
        lv_label_set_text(l, names[i]);
        lv_obj_set_pos(l, cx, cy + 52);
    }
    lv_screen_load(scr);
}

/* Real OSM roads around 100 Feet Road, Indiranagar, with an OSRM route (see tools/osm_demo.py). */
static void map_scenes(void)
{
    static const map_data_t data = {
        .pts = demo_pts, .lines = demo_lines, .nlines = sizeof(demo_lines) / sizeof(demo_lines[0]),
        .route = demo_route, .nroute = sizeof(demo_route) / sizeof(demo_route[0]),
    };
    ui_set_map(&data);

    /* Riding north on 100 Feet Road, right turn coming up in ~235 m. */
    nav_state_t n = base_nav();
    n.maneuver = MANEUVER_RIGHT;
    n.dist_to_turn_m = 480;
    n.dist_left_m = 1100;
    n.time_left_s = 5 * 60;
    strcpy(n.primary, "Chinmaya Mission Hospital Rd");
    ui_set_map_pose(-189, 140, -3.0f, ROUND ? 1.2f : 1.5f);
    ui_set_nav(&n);
    run(600);
    n.dist_to_turn_m = 235;
    ui_set_map_pose(-189, 380, -3.0f, ROUND ? 1.2f : 1.5f);
    ui_set_nav(&n);
    run(1300);   /* let the glide to the new pose finish */
    snap(ROUND ? "r1_map_cruise" : "11_map_cruise");

    /* Just turned east, left onto 2nd Cross Road in ~130 m. */
    n.maneuver = MANEUVER_LEFT;
    n.dist_to_turn_m = 300;
    n.dist_left_m = 520;
    n.time_left_s = 2 * 60;
    strcpy(n.primary, "2nd Cross Road");
    ui_set_map_pose(-200, 616, 87.7f, ROUND ? 0.9f : 1.0f);
    ui_set_nav(&n);
    run(600);
    n.dist_to_turn_m = 130;
    ui_set_map_pose(-110, 620, 87.7f, ROUND ? 0.9f : 1.0f);
    ui_set_nav(&n);
    run(1300);   /* let the glide to the new pose finish */
    snap(ROUND ? "r2_map_near" : "12_map_near");

    /* Zoomed out at speed on the long straight. */
    n.maneuver = MANEUVER_RIGHT;
    n.dist_to_turn_m = 760;
    n.speed_kmh_x10 = 380;
    strcpy(n.primary, "Chinmaya Mission Hospital Rd");
    ui_set_map_pose(-176, -150, -3.0f, ROUND ? 2.2f : 2.6f);
    ui_set_nav(&n);
    run(600);
    snap(ROUND ? "r3_map_fast" : "13_map_fast");

    /* Turn now, onto a name too long for one line. */
    n.speed_kmh_x10 = 220;
    n.dist_to_turn_m = 20;
    strcpy(n.primary, "Old Airport Road Service Lane towards Marathahalli");
    ui_set_map_pose(-189, 595, -3.0f, ROUND ? 1.2f : 1.5f);
    ui_set_nav(&n);
    run(600);
    snap(ROUND ? "r4_map_now" : "14_map_now");

    /* Stopped at a light: time left and arrival join the turn. */
    n.maneuver = MANEUVER_LEFT;
    n.speed_kmh_x10 = 0;
    n.dist_to_turn_m = 180;
    n.dist_left_m = 3400;
    n.time_left_s = 12 * 60;
    n.eta_min_of_day = 10 * 60 + 46;
    strcpy(n.primary, "100 Feet Road / SK Karim Khan Road");
    ui_set_map_pose(-189, 380, -3.0f, ROUND ? 1.2f : 1.5f);
    ui_set_nav(&n);
    run(600);
    snap(ROUND ? "r5_map_stopped" : "15_map_stopped");
}

/* ---------- benchmark (bench/ui_bench.c) ---------- */

static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static int bench(void)
{
    ui_set_link(true);
    ui_bench_run(&(ui_bench_env_t){.now = now_ns, .per_us = 1000, .px = &px_flushed, .flushes = &flushes, .run = run});
    return 0;
}

/*
 * Replays a capture written by the Android app's tests through the same decoders
 * the firmware uses. Records: u8 kind | u16 len | payload, where kind is the
 * characteristic (1 STATE, 3 CTRL, 5 MAP) or 0xFF to take a screenshot named by the payload.
 */
static int replay(const char *path)
{
    FILE *f = fopen(path, "rb");
    if (!f) {
        perror(path);
        return 1;
    }
    static map_buf_t buf;
    map_rx_t rx = {.buf = &buf};
    uint16_t shown = 0;
    uint8_t hdr[3], b[512];
    while (fread(hdr, 1, 3, f) == 3) {
        uint16_t len = (uint16_t)(hdr[1] | hdr[2] << 8);
        if (len > sizeof(b) || fread(b, 1, len, f) != len) break;
        if (hdr[0] == 1) {
            nav_state_t n;
            if (nav_decode(b, len, &n)) ui_set_nav(&n);
            else fprintf(stderr, "bad STATE packet\n");
        } else if (hdr[0] == 5) {
            map_pose_t pose;
            switch (map_rx_feed(&rx, b, len, &pose)) {
            case MAP_RX_DONE:
                printf("map %u: %u pts, %u lines, %u route pts\n", buf.id, buf.npts, buf.nlines, buf.nroute);
                break;
            case MAP_RX_POSE:
                if (pose.id == buf.id && shown != buf.id) {
                    map_data_t d = map_buf_data(&buf);
                    ui_set_map(&d);
                    shown = buf.id;
                }
                if (pose.id == shown) ui_set_map_pose(pose.x, pose.y, pose.heading, pose.m_per_px);
                break;
            case MAP_RX_CLEAR:
                ui_set_map(NULL);
                shown = 0;
                break;
            case MAP_RX_BAD:
                fprintf(stderr, "bad MAP message 0x%02x (%u bytes)\n", b[0], len);
                break;
            default:
                break;
            }
        } else if (hdr[0] == 0xFF) {
            b[len < sizeof(b) ? len : sizeof(b) - 1] = 0;
            run(600);
            snap(strdup((char *)b));
        }
    }
    fclose(f);
    contact_sheet();
    printf("wrote %d screens to %s\n", shot_count, out_dir);
    return 0;
}

int main(int argc, char **argv)
{
    out_dir = argc > 1 ? argv[1] : ".";
    lv_init();
    lv_tick_set_cb(tick);
    ROUND = argc > 2 && !strcmp(argv[2], "round");
    if (ROUND) W = H = 412;
    lv_display_t *d = lv_display_create(W, H);
    lv_display_set_color_format(d, LV_COLOR_FORMAT_RGB565);
    lv_display_set_buffers(d, strip, NULL, (uint32_t)(W * DRAW_LINES * 2), LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(d, flush);

    ui_init();
    ui_set_battery(82, false);
    ui_set_clock(22, 34, 1, 2, 10);
    if (argc > 2 && !strcmp(argv[2], "bench")) return bench();
    if (argc > 3 && !strcmp(argv[2], "replay")) {
        ui_set_link(true);
        return replay(argv[3]);
    }
    if (ROUND) {
        ui_set_link(true);
        map_scenes();
        contact_sheet();
        printf("wrote %d screens to %s\n", shot_count, out_dir);
        return 0;
    }
    run(1300);
    snap("01_waiting");

    ui_set_link(true);
    nav_state_t idle;
    nav_state_clear(&idle);
    idle.speed_kmh_x10 = 236;
    ui_set_nav(&idle);
    run(600);
    snap("02_idle");

    nav_state_t n = base_nav();
    n.maneuver = MANEUVER_LEFT;
    n.dist_to_turn_m = 1450;
    strcpy(n.primary, "MG Road");
    strcpy(n.secondary, "toward Brigade Road");
    ui_set_nav(&n);
    run(600);
    n.dist_to_turn_m = 650;
    ui_set_nav(&n);
    run(100);
    snap("03_cruise");

    n.maneuver = MANEUVER_SLIGHT_RIGHT;
    n.dist_to_turn_m = 400;
    n.dist_left_m = 5200;
    n.time_left_s = 14 * 60;
    strcpy(n.primary, "Outer Ring Road Service Lane");
    strcpy(n.secondary, "toward Marathahalli");
    ui_set_nav(&n);
    run(600);
    n.dist_to_turn_m = 150;
    ui_set_nav(&n);
    run(100);
    snap("04_near");

    n.maneuver = MANEUVER_RIGHT;
    n.dist_to_turn_m = 310;
    strcpy(n.primary, "80 Feet Road");
    strcpy(n.secondary, "toward Koramangala 4th Block");
    ui_set_nav(&n);
    run(600);
    n.dist_to_turn_m = 20;
    ui_set_nav(&n);
    run(100);
    snap("05_turn_now");

    n.maneuver = MANEUVER_ROUNDABOUT;
    n.exit_num = 2;
    n.dist_to_turn_m = 1200;
    n.dist_left_m = 3900;
    n.time_left_s = 9 * 60;
    strcpy(n.primary, "Sony World Junction");
    strcpy(n.secondary, "2nd exit onto Hosur Road");
    ui_set_nav(&n);
    run(600);
    snap("06_roundabout");

    static uint8_t icon[NAV_ICON_BYTES];
    synth_icon(icon);
    n.exit_num = 0;
    n.icon_hash = 0xC0FFEE01;
    n.dist_to_turn_m = 450;
    strcpy(n.primary, "Hosur Road");
    strcpy(n.secondary, "Google Maps icon (bitmap path)");
    ui_set_icon(n.icon_hash, icon);
    ui_set_nav(&n);
    run(600);
    snap("07_gmaps_icon");

    n.flags |= NAV_F_REROUTING;
    n.icon_hash = 0;
    ui_set_nav(&n);
    run(400);
    snap("08_rerouting");

    n = base_nav();
    n.flags = NAV_F_NAVIGATING | NAV_F_ARRIVED;
    n.maneuver = MANEUVER_ARRIVE;
    strcpy(n.primary, "Third Wave Coffee, Indiranagar");
    ui_set_nav(&n);
    run(600);
    snap("09_arrived");

    icon_sheet();
    run(50);
    snap("10_icons");

    map_scenes();

    contact_sheet();
    printf("wrote %d screens to %s\n", shot_count, out_dir);
    return 0;
}
