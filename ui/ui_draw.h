/* Vector widgets drawn with LVGL's anti-aliased primitives: crisp at any size, no image assets. */
#pragma once

#include "lvgl.h"
#include "nav_model.h"

/* Turn arrow for a maneuver_t. `bg` is used for cut-outs (e.g. the hole in the destination pin). */
lv_obj_t *ui_maneuver_create(lv_obj_t *parent, int32_t size);
void ui_maneuver_set(lv_obj_t *obj, uint8_t maneuver, uint8_t exit_num, lv_color_t ink, lv_color_t bg);

/* Bluetooth rune; colour and stroke come from the object's line_color / line_width styles. */
lv_obj_t *ui_ble_create(lv_obj_t *parent, int32_t w, int32_t h);

/* Battery gauge. pct < 0 shows an empty outline. */
lv_obj_t *ui_battery_create(lv_obj_t *parent);
void ui_battery_set(lv_obj_t *obj, int pct, bool charging);
