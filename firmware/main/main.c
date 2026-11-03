/*
 * Raah: glanceable bike/scooter navigation on an ESP32-C3 (or -S3) + SPI TFT.
 *
 * The phone app routes the ride (or mirrors Google Maps) and streams turn-by-turn
 * state plus nearby road geometry over BLE. This task owns LVGL, applies incoming
 * events to the UI and decides how bright the screen should be: full near a turn,
 * dimmed (or off) while cruising, asleep when the phone is gone.
 */
#include <stdio.h>
#include <string.h>
#include <sys/time.h>
#include <time.h>

#include "app_events.h"
#include "ble_link.h"
#include "display.h"
#include "esp_attr.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_pm.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "lvgl.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "power.h"
#include "sdkconfig.h"
#include "ui.h"
#if CONFIG_RAAH_BENCH
#include "ui_bench.h"
#endif

static const char *TAG = "app";

#define BUTTON_WAKE_MS   12000   /* screen stays bright after a button press   */
#define STEP_WAKE_MS     8000    /* ...after the next maneuver appears          */
#define IDLE_WAKE_MS     30000   /* ...after connecting or finishing a route    */
#define ARRIVED_SHOW_MS  20000
#define PERF_LOG_MS      10000   /* frame-time line in the log while drawing     */
#define DIM_PCT          20      /* cruise level in smart-dim, % of full        */
#define LOW_BATT_PCT     15
#define CRIT_BATT_MV     3350

static const char *const bl_mode_names[BL_MODE_COUNT] = {"Always on", "Smart dim", "Smart off"};

static QueueHandle_t queue;
static esp_pm_lock_handle_t cpu_lock;

/* Survive deep sleep so the clock is right before the phone reconnects. */
RTC_DATA_ATTR static char rtc_tz[24];

static struct {
    app_settings_t s;
    bool connected;
    nav_state_t nav;
    int64_t t_disconnected, t_button, t_step, t_activity, t_arrived;
    uint32_t step_key;
    int batt_pct;
    bool charging, warned_low;
    int crit_count;
    int last_minute;
    uint16_t map_id;    /* map on screen, 0 = none */
} app;

void app_post(const app_event_t *ev)
{
    if (queue && xQueueSend(queue, ev, 0) != pdTRUE) ESP_LOGW(TAG, "event %d dropped", ev->type);
}

static int64_t now_ms(void)
{
    return esp_timer_get_time() / 1000;
}

/* ---------- settings ---------- */

static void settings_load(void)
{
    app.s = (app_settings_t){.bl_mode = BL_MODE_SMART_DIM, .max_brightness = 100};
    nvs_handle_t h;
    if (nvs_open("raah", NVS_READONLY, &h) == ESP_OK) {
        size_t len = sizeof(app.s);
        nvs_get_blob(h, "settings", &app.s, &len);
        nvs_close(h);
    }
    if (app.s.bl_mode >= BL_MODE_COUNT) app.s.bl_mode = BL_MODE_SMART_DIM;
    if (app.s.max_brightness < 10 || app.s.max_brightness > 100) app.s.max_brightness = 100;
}

static void settings_save(void)
{
    nvs_handle_t h;
    if (nvs_open("raah", NVS_READWRITE, &h) != ESP_OK) return;
    nvs_set_blob(h, "settings", &app.s, sizeof(app.s));
    nvs_commit(h);
    nvs_close(h);
}

/* ---------- time ---------- */

static void apply_time(uint32_t utc, int16_t offset_min)
{
    struct timeval tv = {.tv_sec = utc};
    settimeofday(&tv, NULL);
    int a = offset_min < 0 ? -offset_min : offset_min;
    /* POSIX TZ offsets are west-positive, so UTC+5:30 is "LOC-5:30". */
    snprintf(rtc_tz, sizeof(rtc_tz), "LOC%c%d:%02d", offset_min >= 0 ? '-' : '+', a / 60, a % 60);
    setenv("TZ", rtc_tz, 1);
    tzset();
    app.last_minute = -1;
}

static void update_clock(void)
{
    if (!rtc_tz[0]) return;
    time_t t = time(NULL);
    struct tm tm;
    localtime_r(&t, &tm);
    if (tm.tm_min == app.last_minute) return;
    app.last_minute = tm.tm_min;
    ui_set_clock(tm.tm_hour, tm.tm_min, tm.tm_wday, tm.tm_mday, tm.tm_mon);
}

/* ---------- sleep ---------- */

static void go_to_sleep(void)
{
    ESP_LOGI(TAG, "powering down");
    ui_show_sleep();
    lv_refr_now(NULL);
    if (display_get_backlight() > 0) vTaskDelay(pdMS_TO_TICKS(1500));
    display_set_backlight(0, 400);
    power_deep_sleep();
}

/* ---------- events ---------- */

static uint32_t step_key(const nav_state_t *n)
{
    uint32_t h = 2166136261u ^ n->maneuver ^ n->icon_hash;
    for (const char *p = n->primary; *p; p++) h = (h ^ (uint8_t)*p) * 16777619u;
    return h;
}

static int full_brightness(void);
static int target_brightness(int64_t now);

static void handle(const app_event_t *ev)
{
    int64_t now = now_ms();
    switch (ev->type) {
    case APP_EV_CONNECTED:
        app.connected = true;
        app.t_activity = now;
        ui_set_link(true);
        break;

    case APP_EV_DISCONNECTED:
        app.connected = false;
        app.t_disconnected = now;
        nav_state_clear(&app.nav);
        app.map_id = 0;
        ui_set_map(NULL);
        ui_set_link(false);
        break;

    case APP_EV_POSE: {
        const map_pose_t *p = &ev->pose;
        if (p->id != app.map_id) {
            const map_buf_t *m = ble_link_map_adopt(p->id);
            if (!m) break;      /* pose for a map we never completed */
            map_data_t d = map_buf_data(m);
            ui_set_map(&d);
            app.map_id = p->id;
        }
        ui_set_map_pose(p->x, p->y, p->heading, p->m_per_px);
        break;
    }

    case APP_EV_MAP_CLEAR:
        app.map_id = 0;
        ui_set_map(NULL);
        break;

    case APP_EV_NAV: {
        const nav_state_t *n = &ev->nav;
        bool was_nav = app.nav.flags & NAV_F_NAVIGATING, is_nav = n->flags & NAV_F_NAVIGATING;
        if (is_nav != was_nav) app.t_activity = now;
        if ((n->flags & NAV_F_ARRIVED) && !(app.nav.flags & NAV_F_ARRIVED)) app.t_arrived = now;
        uint32_t key = step_key(n);
        if (is_nav && key != app.step_key) app.t_step = now;
        app.step_key = key;
        app.nav = *n;
        ui_set_nav(n);
        break;
    }

    case APP_EV_ICON:
        ble_link_icon_apply(ev->icon_slot, ui_set_icon);
        break;

    case APP_EV_TIME:
        apply_time(ev->time.utc, ev->time.offset_min);
        update_clock();
        break;

    case APP_EV_SETTINGS:
        app.s = ev->settings;
        if (app.s.bl_mode >= BL_MODE_COUNT) app.s.bl_mode = BL_MODE_SMART_DIM;
        if (app.s.max_brightness < 10 || app.s.max_brightness > 100) app.s.max_brightness = 100;
        settings_save();
        ui_set_prefs(app.s.imperial, app.s.use_24h);
        break;

    case APP_EV_BUTTON: {
        /* A click on a dark or dimmed screen only wakes it; on a lit one it flips map / turn card. */
        bool lit = display_get_backlight() > 0 && target_brightness(now) >= full_brightness();
        app.t_button = now;
        if (ev->button == BTN_CLICK) {
            if (lit) ui_toggle_view();
        } else if (ev->button == BTN_DOUBLE) {
            app.s.bl_mode = (app.s.bl_mode + 1) % BL_MODE_COUNT;
            settings_save();
            char msg[32];
            snprintf(msg, sizeof(msg), "Screen: %s", bl_mode_names[app.s.bl_mode]);
            ui_show_toast(msg, 2500);
        } else if (ev->button == BTN_LONG) {
            go_to_sleep();
        }
        break;
    }

    case APP_EV_BATTERY:
        app.batt_pct = ev->battery.pct;
        app.charging = ev->battery.charging;
        ui_set_battery(app.batt_pct, app.charging);
        ble_link_set_status(app.batt_pct, ev->battery.mv, app.charging);
        bool has_cell = app.batt_pct >= 0;
        if (has_cell && !app.charging && app.batt_pct <= LOW_BATT_PCT && !app.warned_low) {
            app.warned_low = true;
            char msg[32];
            snprintf(msg, sizeof(msg), "Battery low \xE2\x80\xA2 %d%%", app.batt_pct);
            ui_show_toast(msg, 5000);
        } else if (app.batt_pct > LOW_BATT_PCT + 5) {
            app.warned_low = false;
        }
        app.crit_count = (has_cell && !app.charging && ev->battery.mv < CRIT_BATT_MV) ? app.crit_count + 1 : 0;
        if (app.crit_count >= 3) go_to_sleep();
        break;
    }
}

/* ---------- backlight policy ---------- */

static int full_brightness(void)
{
    int full = app.s.max_brightness;
    int amb = power_ambient_pct();
    if (amb >= 0) full = full * (25 + 75 * amb / 100) / 100;   /* 25 % at night .. 100 % in sun */
    return full < 5 ? 5 : full;
}

static int target_brightness(int64_t now)
{
    int full = full_brightness();
    bool pressed = app.t_button && now - app.t_button < BUTTON_WAKE_MS;

    if (!app.connected) {
        bool fresh = now - app.t_disconnected < CONFIG_RAAH_WAIT_SCREEN_S * 1000LL;
        return (fresh || pressed) ? full * 6 / 10 : 0;
    }
    if (app.s.bl_mode == BL_MODE_ALWAYS) return full;

    const nav_state_t *n = &app.nav;
    bool navigating = n->flags & NAV_F_NAVIGATING;
    bool busy = pressed || now - app.t_activity < IDLE_WAKE_MS ||
                (navigating && (now - app.t_step < STEP_WAKE_MS || nav_urgency(n) != NAV_URGENCY_FAR ||
                                (n->flags & NAV_F_REROUTING))) ||
                ((n->flags & NAV_F_ARRIVED) && now - app.t_arrived < ARRIVED_SHOW_MS);
    if (busy) return full;
    return app.s.bl_mode == BL_MODE_SMART_DIM ? LV_MAX(full * DIM_PCT / 100, 4) : 0;
}

/* One line per PERF_LOG_MS while the screen is changing: how busy drawing kept the CPU. */
static void perf_log(int64_t now)
{
    static int64_t t_last;
    if (now - t_last < PERF_LOG_MS) return;
    display_stats_t s;
    display_stats_take(&s);
    if (s.frames) {
        ESP_LOGI(TAG, "ui: %lu frames in %lld s, %lu ms avg, %lu ms max, %llu kpx, backlight %d%%; heap %u KB min %u KB",
                 (unsigned long)s.frames, (now - t_last) / 1000, (unsigned long)(s.busy_us / s.frames / 1000),
                 (unsigned long)(s.max_us / 1000), (unsigned long long)(s.px / 1000), display_get_backlight(),
                 (unsigned)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024),
                 (unsigned)(heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL) / 1024));
    }
    t_last = now;
}

static void policy(void)
{
    int64_t now = now_ms();
    update_clock();
    perf_log(now);

    int target = target_brightness(now), cur = display_get_backlight();
    if (target != cur) {
        if (!target || !cur) ESP_LOGI(TAG, "screen %s", target ? "on" : "off");
        esp_pm_lock_acquire(cpu_lock);      /* waking redraws the whole screen */
        display_set_backlight(target, target > cur ? 120 : 700);
        esp_pm_lock_release(cpu_lock);
    }
    ui_set_animations(target > 0);
    ui_set_calm(target > 0 && target < full_brightness());

    if (!app.connected && now - app.t_disconnected > CONFIG_RAAH_DEEP_SLEEP_MIN * 60000LL &&
        now - app.t_button > CONFIG_RAAH_DEEP_SLEEP_MIN * 60000LL) {
        go_to_sleep();
    }
}

/* ---------- UI task ---------- */

#if CONFIG_RAAH_BENCH
static uint64_t bench_now(void)
{
    return (uint64_t)esp_timer_get_time();
}

static void bench_yield(void)
{
    vTaskDelay(1);
}

static void bench_run(uint32_t ms)
{
    for (int64_t end = esp_timer_get_time() + ms * 1000; esp_timer_get_time() < end;) {
        lv_timer_handler();
        vTaskDelay(pdMS_TO_TICKS(5));
    }
}

/* Ten seconds of labelled swatches (the UI's own greys and accent, plus pure colours) and a TOP marker,
 * for checking orientation and colour on a new panel from a photo. */
static void test_card(void)
{
    static const struct {
        uint32_t rgb;
        const char *name;
    } sw[] = {
        {0x000000, "000000 black"}, {0x12161B, "12161B card"},  {0x2B3139, "2B3139 road"},
        {0x535C69, "535C69 major"}, {0x9AA3B0, "9AA3B0 text 2"}, {0xFFFFFF, "FFFFFF white"},
        {0xFF0000, "FF0000 red"},   {0x00FF00, "00FF00 green"}, {0x0000FF, "0000FF blue"},
        {0x2EE59D, "2EE59D accent"},
    };
    lv_obj_t *prev = lv_screen_active(), *scr = lv_obj_create(NULL);
    lv_obj_remove_style_all(scr);
    lv_obj_set_style_bg_color(scr, lv_color_hex(0x404040), 0);
    lv_obj_set_style_bg_opa(scr, LV_OPA_COVER, 0);
    lv_obj_t *top = lv_label_create(scr);
    lv_obj_set_style_text_color(top, lv_color_white(), 0);
    lv_label_set_text(top, LV_SYMBOL_UP " TOP " LV_SYMBOL_UP);
    lv_obj_align(top, LV_ALIGN_TOP_MID, 0, 4);
    for (int i = 0; i < (int)(sizeof(sw) / sizeof(sw[0])); i++) {
        lv_obj_t *r = lv_obj_create(scr);
        lv_obj_remove_style_all(r);
        lv_obj_set_style_bg_color(r, lv_color_hex(sw[i].rgb), 0);
        lv_obj_set_style_bg_opa(r, LV_OPA_COVER, 0);
        lv_obj_set_size(r, 90, 24);
        lv_obj_set_pos(r, 8, 28 + i * 28);
        lv_obj_t *l = lv_label_create(scr);
        lv_obj_set_style_text_color(l, lv_color_white(), 0);
        lv_label_set_text(l, sw[i].name);
        lv_obj_set_pos(l, 106, 32 + i * 28);
    }
    lv_obj_t *left = lv_label_create(scr);
    lv_obj_set_style_text_color(left, lv_color_white(), 0);
    lv_label_set_text(left, LV_SYMBOL_LEFT " LEFT");
    lv_obj_align(left, LV_ALIGN_BOTTOM_LEFT, 4, -4);
    lv_screen_load(scr);
    lv_refr_now(NULL);
    vTaskDelay(pdMS_TO_TICKS(10000));
    lv_screen_load(prev);
    lv_obj_delete(scr);
}

/* Times the map screen at full CPU clock, with the panel and without it, then hands over to the app. */
static void bench(void)
{
    display_set_backlight(100, 0);       /* the app's brightness policy only starts after the benchmark */
    test_card();                         /* also gives the console time to attach */
    esp_pm_lock_acquire(cpu_lock);
    ui_set_link(true);
    static const char *const passes[] = {"render + SPI to the panel", "render only (SPI skipped)",
                                         "render only, square line ends", "render only, no map"};
    for (int pass = 0; pass < 4; pass++) {
        display_skip_spi = pass > 0;
        ui_map_knobs = (ui_map_knobs_t){.no_caps = pass == 2, .no_roads = pass == 3, .no_route = pass == 3};
        memset(display_strip_us, 0, sizeof(display_strip_us));
        uint64_t swap0 = display_swap_cycles, px0 = display_px;
        printf("\n== %s, CPU %d MHz, SPI %d MHz, circle cache %d\n", passes[pass], CONFIG_ESP_DEFAULT_CPU_FREQ_MHZ,
               CONFIG_RAAH_LCD_SPI_MHZ, LV_DRAW_SW_CIRCLE_CACHE_SIZE);
        ui_bench_run(&(ui_bench_env_t){.now = bench_now, .per_us = 1, .px = &display_px,
                                       .flushes = &display_flushes, .run = bench_run,
                                       .yield = bench_yield});
        printf("byte swap: %.2f cycles/pixel\n",
               (double)(display_swap_cycles - swap0) / (double)(display_px - px0 ? display_px - px0 : 1));
        if (pass == 1) ui_bench_parts(&(ui_bench_env_t){.now = bench_now, .per_us = 1, .px = &display_px,
                                                           .flushes = &display_flushes, .run = bench_run,
                                                           .yield = bench_yield});
        if (pass > 0) {
            printf("ms per %d-row strip, top first (all scenarios, per riding frame):", DISPLAY_LINES);
            for (int i = 0; i < DISPLAY_STRIPS; i++) printf(" %.1f", display_strip_us[i] / 60.0 / 1000.0);
            printf("\n");
        }
    }
    display_skip_spi = false;
    ui_map_knobs = (ui_map_knobs_t){0};
    app.t_disconnected = now_ms();      /* the waiting screen gets its full time after the benchmark */
    esp_pm_lock_release(cpu_lock);
    printf("internal heap: %u KB free, %u KB largest block, %u KB minimum ever\n",
           (unsigned)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024),
           (unsigned)(heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL) / 1024),
           (unsigned)(heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL) / 1024));
}
#endif

static void ui_task(void *arg)
{
    esp_pm_lock_acquire(cpu_lock);
    display_init();
    ui_init();
    ui_set_prefs(app.s.imperial, app.s.use_24h);
    update_clock();
    lv_refr_now(NULL);
    esp_pm_lock_release(cpu_lock);
    ESP_LOGI(TAG, "internal heap: %u KB free, %u KB largest block",
             (unsigned)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024),
             (unsigned)(heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL) / 1024));

#if CONFIG_RAAH_BENCH
    bench();
#endif

    for (;;) {
        esp_pm_lock_acquire(cpu_lock);
        uint32_t wait = lv_timer_handler();
        esp_pm_lock_release(cpu_lock);

        /* Sleep until LVGL's next timer, an event, or the 1 s policy tick. */
        if (wait > 1000) wait = 1000;
        app_event_t ev;
        if (xQueueReceive(queue, &ev, pdMS_TO_TICKS(wait) ? pdMS_TO_TICKS(wait) : 1) == pdTRUE) {
            esp_pm_lock_acquire(cpu_lock);
            do {
                handle(&ev);
            } while (xQueueReceive(queue, &ev, 0) == pdTRUE);
            esp_pm_lock_release(cpu_lock);
        }
        policy();
    }
}

void app_main(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    settings_load();
    app.batt_pct = -1;
    app.last_minute = -1;
    nav_state_clear(&app.nav);
    if (rtc_tz[0]) {
        setenv("TZ", rtc_tz, 1);
        tzset();
    }

    queue = xQueueCreate(16, sizeof(app_event_t));
    ESP_ERROR_CHECK(esp_pm_lock_create(ESP_PM_CPU_FREQ_MAX, 0, "ui", &cpu_lock));
    power_init();
    /* The last core: on the S3 the second one, clear of BLE on core 0; on the C3 its only one. */
    xTaskCreatePinnedToCore(ui_task, "ui", 8192, NULL, 4, NULL, CONFIG_FREERTOS_NUMBER_OF_CORES - 1);
    ble_link_init();
}
