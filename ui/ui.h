/*
 * Raah screens. Pure LVGL, no platform code: the firmware and the host
 * simulator both drive it through this API. Call only from the LVGL thread.
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "nav_model.h"
#include "ui_map.h"

#ifdef __cplusplus
extern "C" {
#endif

void ui_init(void);

void ui_set_link(bool connected);
void ui_set_nav(const nav_state_t *nav);
/* Registers a NAV_ICON_PX² A8 bitmap for `hash` (data is copied). */
void ui_set_icon(uint32_t hash, const uint8_t *a8);
bool ui_has_icon(uint32_t hash);
/* Road geometry for the map screen (referenced, not copied). NULL hides the map. */
void ui_set_map(const map_data_t *data);
/* Rider position in metres in the map's frame, heading in degrees from north, zoom in metres per pixel.
 * The map screen shows once both geometry and a pose are set. */
void ui_set_map_pose(float x, float y, float heading, float m_per_px);
void ui_set_battery(int pct, bool charging);
/* hour < 0 means the time is not known yet. */
void ui_set_clock(int hour, int minute, int wday, int mday, int month);
void ui_set_prefs(bool imperial, bool use_24h);

/* Flips navigation between the map and the big turn card. Returns false when there is no map to flip to. */
bool ui_toggle_view(void);

void ui_show_toast(const char *text, uint32_t ms);
void ui_show_sleep(void);
/* Stops looping animations (e.g. while the backlight is off) so the CPU can sleep. */
void ui_set_animations(bool enable);
/* With the screen dimmed, the map glides in coarser steps, at most 4 frames a second. */
void ui_set_calm(bool calm);

#ifdef __cplusplus
}
#endif
