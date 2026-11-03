#pragma once

#include "lvgl.h"

/*
 * Palette for a glance-at-speed screen in daylight: pure black ground, white figures, and four
 * saturated, high-luminance signal hues, each with exactly one meaning:
 *   GO    green   route ahead, progress, brand
 *   NEAR  yellow  turn coming up; "turn now" fills the whole panel with it, like a hi-vis sign
 *   LINK  blue    phone / connection / the system working (rerouting)
 *   ALERT red     battery and faults only
 * Greys only carry secondary text and the map; on a TN panel anything darker than TEXT3 vanishes
 * off-axis, so nothing the rider must read uses it.
 */
#define UI_C_BG         lv_color_hex(0x000000)
#define UI_C_CARD       lv_color_hex(0x15191F)
#define UI_C_CARD_EDGE  lv_color_hex(0x232932)
#define UI_C_LINE       lv_color_hex(0x2C333D)
#define UI_C_TEXT       lv_color_hex(0xFFFFFF)
#define UI_C_TEXT2      lv_color_hex(0xB4BDC9)
#define UI_C_TEXT3      lv_color_hex(0x737D8C)
#define UI_C_ACCENT     lv_color_hex(0x1FF08A)   /* GO    */
#define UI_C_NEAR       lv_color_hex(0xFFD000)   /* NEAR  */
#define UI_C_LINK       lv_color_hex(0x38AEFF)   /* LINK  */
#define UI_C_ALERT      lv_color_hex(0xFF4545)   /* ALERT */
#define UI_C_ON_NEAR    lv_color_hex(0x000000)   /* ink on the yellow panel */
#define UI_C_ON_NEAR2   lv_color_hex(0x4D3E00)   /* secondary ink on it     */

LV_FONT_DECLARE(font_num_96)
LV_FONT_DECLARE(font_num_64)
LV_FONT_DECLARE(font_num_44)
LV_FONT_DECLARE(font_bold_28)
LV_FONT_DECLARE(font_semi_26)
LV_FONT_DECLARE(font_semi_22)
LV_FONT_DECLARE(font_med_18)
LV_FONT_DECLARE(font_label_12)
