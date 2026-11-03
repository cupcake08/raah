#include "display.h"

#include <math.h>

#include "driver/gpio.h"
#include "driver/ledc.h"
#include "driver/spi_master.h"
#include "esp_check.h"
#include "esp_cpu.h"
#include "esp_heap_caps.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_vendor.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "lvgl.h"
#include "sdkconfig.h"

#if CONFIG_RAAH_LCD_ILI9341
#include "esp_lcd_ili9341.h"
#endif

static const char *TAG = "display";

/* Kconfig leaves disabled bools undefined; give them a 0. */
#ifndef CONFIG_RAAH_LCD_INVERT
#define CONFIG_RAAH_LCD_INVERT 0
#endif
#ifndef CONFIG_RAAH_LCD_BGR
#define CONFIG_RAAH_LCD_BGR 0
#endif
#ifndef CONFIG_RAAH_LCD_SWAP_XY
#define CONFIG_RAAH_LCD_SWAP_XY 0
#endif
#ifndef CONFIG_RAAH_LCD_MIRROR_X
#define CONFIG_RAAH_LCD_MIRROR_X 0
#endif
#ifndef CONFIG_RAAH_LCD_MIRROR_Y
#define CONFIG_RAAH_LCD_MIRROR_Y 0
#endif
#ifndef CONFIG_RAAH_LCD_BL_ACTIVE_LOW
#define CONFIG_RAAH_LCD_BL_ACTIVE_LOW 0
#endif

#define H_RES       CONFIG_RAAH_LCD_H_RES
#define V_RES       CONFIG_RAAH_LCD_V_RES
#define BL_TIMER    LEDC_TIMER_0
#define BL_CHANNEL  LEDC_CHANNEL_0
#define BL_RES      LEDC_TIMER_10_BIT
#define BL_MAX_DUTY ((1 << 10) - 1)

#if CONFIG_RAAH_LCD_ILI9341
/*
 * Power, VCOM and gamma for the generic red-PCB ILI9341 modules: the reference sequence shipped with them
 * (also used by Adafruit_ILI9341 and TFT_eSPI). The esp_lcd driver's default is tuned for the ESP-WROVER-KIT
 * panel; its VCOM (C5h 43 4C, C7h A0) washes these modules out, so dark greys come up nearly white.
 */
static const ili9341_lcd_init_cmd_t ili9341_init[] = {
    {0xEF, (uint8_t[]){0x03, 0x80, 0x02}, 3, 0},
    {0xCF, (uint8_t[]){0x00, 0xC1, 0x30}, 3, 0},             /* power control B */
    {0xED, (uint8_t[]){0x64, 0x03, 0x12, 0x81}, 4, 0},       /* power-on sequence */
    {0xE8, (uint8_t[]){0x85, 0x00, 0x78}, 3, 0},             /* driver timing A */
    {0xCB, (uint8_t[]){0x39, 0x2C, 0x00, 0x34, 0x02}, 5, 0}, /* power control A */
    {0xF7, (uint8_t[]){0x20}, 1, 0},                         /* pump ratio */
    {0xEA, (uint8_t[]){0x00, 0x00}, 2, 0},                   /* driver timing B */
    {0xC0, (uint8_t[]){0x23}, 1, 0},                         /* power control 1: GVDD 4.60 V */
    {0xC1, (uint8_t[]){0x10}, 1, 0},                         /* power control 2 */
    {0xC5, (uint8_t[]){0x3E, 0x28}, 2, 0},                   /* VCOM 1: VCOMH 4.25 V, VCOML -1.5 V */
    {0xC7, (uint8_t[]){0x86}, 1, 0},                         /* VCOM 2 offset */
    {0x37, (uint8_t[]){0x00}, 1, 0},                         /* vertical scroll start 0 */
    {0xB1, (uint8_t[]){0x00, 0x18}, 2, 0},                   /* frame rate 79 Hz */
    {0xB6, (uint8_t[]){0x08, 0x82, 0x27}, 3, 0},             /* display function control */
    {0xF2, (uint8_t[]){0x00}, 1, 0},                         /* 3-gamma off */
    {0x26, (uint8_t[]){0x01}, 1, 0},                         /* gamma curve 1 */
    {0xE0, (uint8_t[]){0x0F, 0x31, 0x2B, 0x0C, 0x0E, 0x08, 0x4E, 0xF1, 0x37, 0x07, 0x10, 0x03, 0x0E, 0x09, 0x00}, 15, 0},
    {0xE1, (uint8_t[]){0x00, 0x0E, 0x14, 0x03, 0x11, 0x07, 0x31, 0xC1, 0x48, 0x08, 0x0F, 0x0C, 0x31, 0x36, 0x0F}, 15, 0},
};
#endif

static esp_lcd_panel_handle_t panel;
static lv_display_t *disp;
static int bl_pct = -1;
static bool panel_asleep;

static uint32_t tick_ms(void)
{
    return (uint32_t)(esp_timer_get_time() / 1000);
}

static bool on_trans_done(esp_lcd_panel_io_handle_t io, esp_lcd_panel_io_event_data_t *edata, void *ctx)
{
    lv_display_flush_ready((lv_display_t *)ctx);
    return false;
}

/* Frame timing for the field log: start of rendering to the last strip handed to SPI. */
static display_stats_t stats;
static int64_t frame_t0;

#if CONFIG_RAAH_BENCH
uint64_t display_px, display_flushes, display_swap_cycles;
bool display_skip_spi;
uint64_t display_strip_us[DISPLAY_STRIPS];
static int64_t strip_t0;
#endif

static void render_start(lv_event_t *e)
{
    frame_t0 = esp_timer_get_time();
#if CONFIG_RAAH_BENCH
    strip_t0 = frame_t0;
#endif
}

void display_stats_take(display_stats_t *out)
{
    *out = stats;
    stats = (display_stats_t){0};
}

static void flush_cb(lv_display_t *disp, const lv_area_t *area, uint8_t *px)
{
#if CONFIG_RAAH_BENCH
    int64_t now = esp_timer_get_time();
    display_strip_us[area->y1 / DISPLAY_LINES] += now - strip_t0;
    uint32_t c0 = esp_cpu_get_cycle_count();
#endif
    /* The panel wants big-endian RGB565. */
    lv_draw_sw_rgb565_swap(px, lv_area_get_size(area));
    stats.px += lv_area_get_size(area);
    if (lv_display_flush_is_last(disp)) {
        uint32_t us = (uint32_t)(esp_timer_get_time() - frame_t0);
        stats.frames++;
        stats.busy_us += us;
        if (us > stats.max_us) stats.max_us = us;
    }
#if CONFIG_RAAH_BENCH
    display_swap_cycles += esp_cpu_get_cycle_count() - c0;
    display_px += lv_area_get_size(area);
    display_flushes++;
    strip_t0 = esp_timer_get_time();
    if (display_skip_spi) {
        lv_display_flush_ready(disp);
        return;
    }
#endif
    esp_lcd_panel_draw_bitmap(panel, area->x1, area->y1, area->x2 + 1, area->y2 + 1, px);
}

static void backlight_init(void)
{
    /* RC_FAST keeps the PWM running through automatic light sleep. */
    ledc_timer_config_t t = {
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .duty_resolution = BL_RES,
        .timer_num = BL_TIMER,
        .freq_hz = 5000,
        .clk_cfg = LEDC_USE_RC_FAST_CLK,
    };
    ESP_ERROR_CHECK(ledc_timer_config(&t));
    ledc_channel_config_t c = {
        .gpio_num = CONFIG_RAAH_PIN_LCD_BL,
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .channel = BL_CHANNEL,
        .timer_sel = BL_TIMER,
        .duty = 0,
        .sleep_mode = LEDC_SLEEP_MODE_KEEP_ALIVE,
        .flags.output_invert = CONFIG_RAAH_LCD_BL_ACTIVE_LOW,
    };
    ESP_ERROR_CHECK(ledc_channel_config(&c));
    ESP_ERROR_CHECK(ledc_fade_func_install(0));
}

void display_set_backlight(int pct, uint32_t fade_ms)
{
    pct = pct < 0 ? 0 : pct > 100 ? 100 : pct;
    if (pct == bl_pct) return;
    if (pct > 0 && panel_asleep) {
        esp_lcd_panel_disp_sleep(panel, false);
        panel_asleep = false;
        /* Nothing was drawn while dark: bring the panel up to date before it lights. */
        lv_display_enable_invalidation(disp, true);
        lv_obj_invalidate(lv_screen_active());
        lv_refr_now(disp);
    }
    /* Gamma 2.2 so equal steps look equal; keep a visible floor above 0. */
    uint32_t duty = pct ? (uint32_t)lroundf(powf(pct / 100.f, 2.2f) * BL_MAX_DUTY) : 0;
    if (pct && duty < 4) duty = 4;
    ledc_fade_stop(LEDC_LOW_SPEED_MODE, BL_CHANNEL);
    if (fade_ms) {
        ledc_set_fade_with_time(LEDC_LOW_SPEED_MODE, BL_CHANNEL, duty, (int)fade_ms);
        ledc_fade_start(LEDC_LOW_SPEED_MODE, BL_CHANNEL, pct ? LEDC_FADE_NO_WAIT : LEDC_FADE_WAIT_DONE);
    } else {
        ledc_set_duty(LEDC_LOW_SPEED_MODE, BL_CHANNEL, duty);
        ledc_update_duty(LEDC_LOW_SPEED_MODE, BL_CHANNEL);
    }
    if (pct == 0 && !panel_asleep) {
        esp_lcd_panel_disp_sleep(panel, true);
        panel_asleep = true;
        /* A dark panel shows nothing, so map moves and clock ticks draw nothing until it lights again. */
        lv_display_enable_invalidation(disp, false);
    }
    bl_pct = pct;
}

int display_get_backlight(void)
{
    return bl_pct < 0 ? 0 : bl_pct;
}

void display_init(void)
{
    backlight_init();

    spi_bus_config_t bus = {
        .sclk_io_num = CONFIG_RAAH_PIN_LCD_SCLK,
        .mosi_io_num = CONFIG_RAAH_PIN_LCD_MOSI,
        .miso_io_num = -1,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = H_RES * DISPLAY_LINES * sizeof(uint16_t),
    };
    ESP_ERROR_CHECK(spi_bus_initialize(SPI2_HOST, &bus, SPI_DMA_CH_AUTO));

    lv_init();
    lv_tick_set_cb(tick_ms);
    disp = lv_display_create(H_RES, V_RES);

    esp_lcd_panel_io_handle_t io;
    esp_lcd_panel_io_spi_config_t io_cfg = {
        .cs_gpio_num = CONFIG_RAAH_PIN_LCD_CS,
        .dc_gpio_num = CONFIG_RAAH_PIN_LCD_DC,
        .spi_mode = 0,
        .pclk_hz = CONFIG_RAAH_LCD_SPI_MHZ * 1000 * 1000,
        .trans_queue_depth = 10,
        .on_color_trans_done = on_trans_done,
        .user_ctx = disp,
        .lcd_cmd_bits = 8,
        .lcd_param_bits = 8,
    };
    ESP_ERROR_CHECK(esp_lcd_new_panel_io_spi((esp_lcd_spi_bus_handle_t)SPI2_HOST, &io_cfg, &io));

#if CONFIG_RAAH_LCD_ILI9341
    ili9341_vendor_config_t vendor = {.init_cmds = ili9341_init, .init_cmds_size = sizeof(ili9341_init) / sizeof(ili9341_init[0])};
#endif
    esp_lcd_panel_dev_config_t dev = {
#if CONFIG_RAAH_LCD_ILI9341
        .vendor_config = &vendor,
#endif
        .reset_gpio_num = CONFIG_RAAH_PIN_LCD_RST,
        .rgb_ele_order = CONFIG_RAAH_LCD_BGR ? LCD_RGB_ELEMENT_ORDER_BGR : LCD_RGB_ELEMENT_ORDER_RGB,
        .bits_per_pixel = 16,
    };
#if CONFIG_RAAH_LCD_ILI9341
    ESP_ERROR_CHECK(esp_lcd_new_panel_ili9341(io, &dev, &panel));
#else
    ESP_ERROR_CHECK(esp_lcd_new_panel_st7789(io, &dev, &panel));
#endif
    ESP_ERROR_CHECK(esp_lcd_panel_reset(panel));
    ESP_ERROR_CHECK(esp_lcd_panel_init(panel));
    ESP_ERROR_CHECK(esp_lcd_panel_invert_color(panel, CONFIG_RAAH_LCD_INVERT));
    ESP_ERROR_CHECK(esp_lcd_panel_swap_xy(panel, CONFIG_RAAH_LCD_SWAP_XY));
    ESP_ERROR_CHECK(esp_lcd_panel_mirror(panel, CONFIG_RAAH_LCD_MIRROR_X, CONFIG_RAAH_LCD_MIRROR_Y));
    ESP_ERROR_CHECK(esp_lcd_panel_set_gap(panel, CONFIG_RAAH_LCD_GAP_X, CONFIG_RAAH_LCD_GAP_Y));
    ESP_ERROR_CHECK(esp_lcd_panel_disp_on_off(panel, true));

    size_t buf_sz = H_RES * DISPLAY_LINES * sizeof(uint16_t);
    void *b1 = heap_caps_malloc(buf_sz, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    void *b2 = heap_caps_malloc(buf_sz, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    assert(b1 && b2);
    lv_display_set_color_format(disp, LV_COLOR_FORMAT_RGB565);
    lv_display_set_buffers(disp, b1, b2, buf_sz, LV_DISPLAY_RENDER_MODE_PARTIAL);
    lv_display_set_flush_cb(disp, flush_cb);
    lv_display_add_event_cb(disp, render_start, LV_EVENT_RENDER_START, NULL);
    ESP_LOGI(TAG, "%dx%d panel up, SPI %d MHz", H_RES, V_RES, CONFIG_RAAH_LCD_SPI_MHZ);
}
