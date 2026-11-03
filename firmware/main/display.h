#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "sdkconfig.h"

/* Rows LVGL renders per strip. Each strip redraws every object crossing it, so taller strips mean less
 * repeated work; two strip buffers live in internal DMA RAM. */
#define DISPLAY_LINES 40

/* Brings up the SPI panel and LVGL. Call from the task that will run lv_timer_handler(). */
void display_init(void);

/* Backlight in percent (perceptual, gamma corrected), faded over fade_ms.
 * 0 turns the backlight off, puts the panel controller to sleep and stops drawing; lighting it again
 * redraws the screen first. Call from the LVGL task. */
void display_set_backlight(int pct, uint32_t fade_ms);
int display_get_backlight(void);

typedef struct {
    uint32_t frames, max_us;
    uint64_t busy_us, px;
} display_stats_t;

/* Frames drawn, time spent and pixels pushed since the last call. */
void display_stats_take(display_stats_t *out);

#if CONFIG_RAAH_BENCH
/* Benchmark hooks: pixels and flushes so far, cycles spent byte-swapping, and a switch that skips the
 * SPI transfer so rendering can be timed on its own. */
extern uint64_t display_px, display_flushes, display_swap_cycles;
extern bool display_skip_spi;
/* Render time per strip, summed (meaningful with the SPI transfer skipped). */
#define DISPLAY_STRIPS ((CONFIG_RAAH_LCD_V_RES + DISPLAY_LINES - 1) / DISPLAY_LINES)
extern uint64_t display_strip_us[DISPLAY_STRIPS];
#endif
