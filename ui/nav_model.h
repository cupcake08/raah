/*
 * Navigation data model and BLE wire format shared by the firmware, the UI and
 * the host simulator. Keep in sync with android/.../NavProtocol.kt.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define NAV_PROTO_VERSION   2
#define NAV_ICON_PX         96          /* maneuver bitmaps are NAV_ICON_PX² A8 */
#define NAV_ICON_BYTES      (NAV_ICON_PX * NAV_ICON_PX)
#define NAV_U32_UNKNOWN     0xFFFFFFFFu
#define NAV_U16_UNKNOWN     0xFFFFu
#define NAV_PRIMARY_MAX     64
#define NAV_SECONDARY_MAX   96

/* GATT layout (128-bit UUIDs, little-endian byte order in firmware). */
#define NAV_UUID_SERVICE    "8c5a1f40-6b2e-4d2a-9b7e-5f3a2c1d0e00"
#define NAV_UUID_STATE      "8c5a1f40-6b2e-4d2a-9b7e-5f3a2c1d0e01" /* write      : nav packet   */
#define NAV_UUID_ICON       "8c5a1f40-6b2e-4d2a-9b7e-5f3a2c1d0e02" /* write NR   : icon chunks  */
#define NAV_UUID_CTRL       "8c5a1f40-6b2e-4d2a-9b7e-5f3a2c1d0e03" /* write      : time/settings */
#define NAV_UUID_STATUS     "8c5a1f40-6b2e-4d2a-9b7e-5f3a2c1d0e04" /* read/notify: device status */
#define NAV_UUID_MAP        "8c5a1f40-6b2e-4d2a-9b7e-5f3a2c1d0e05" /* write NR   : map_proto.h  */

typedef enum {
    MANEUVER_UNKNOWN = 0,
    MANEUVER_STRAIGHT,
    MANEUVER_SLIGHT_LEFT,
    MANEUVER_SLIGHT_RIGHT,
    MANEUVER_LEFT,
    MANEUVER_RIGHT,
    MANEUVER_SHARP_LEFT,
    MANEUVER_SHARP_RIGHT,
    MANEUVER_UTURN_LEFT,
    MANEUVER_UTURN_RIGHT,
    MANEUVER_FORK_LEFT,
    MANEUVER_FORK_RIGHT,
    MANEUVER_MERGE,
    MANEUVER_ROUNDABOUT,
    MANEUVER_ARRIVE,
    MANEUVER_COUNT
} maneuver_t;

enum {
    NAV_F_NAVIGATING = 1 << 0,
    NAV_F_REROUTING  = 1 << 1,
    NAV_F_ARRIVED    = 1 << 2,
};

/*
 * STATE packet (little-endian):
 *   0  u8  version            4  u32 dist_to_turn_m     16 u16 eta_min_of_day (local)
 *   1  u8  flags              8  u32 dist_left_m        18 u16 speed_kmh_x10
 *   2  u8  maneuver          12  u32 time_left_s        20 u32 icon_hash (0 = none)
 *   3  u8  exit_num          24  u8 len + primary UTF-8, u8 len + secondary UTF-8
 *
 * ICON chunk: u32 hash | u16 offset | A8 bytes. Image complete at NAV_ICON_BYTES.
 *
 * CTRL: 0x01 TIME     u32 unix_utc | i16 utc_offset_min
 *       0x02 SETTINGS u8 imperial | u8 use_24h | u8 backlight_mode | u8 max_brightness_pct
 *
 * STATUS: u8 version | u8 battery_pct (0xFF unknown) | u16 battery_mv | u8 charging | u8 icon_px
 *         | u16 map_id (last map received complete, 0 = none)
 */
#define NAV_HDR_LEN     24
#define NAV_STATUS_LEN  8
#define NAV_CTRL_TIME     0x01
#define NAV_CTRL_SETTINGS 0x02

typedef struct {
    uint8_t  flags;
    uint8_t  maneuver;
    uint8_t  exit_num;
    uint32_t dist_to_turn_m;
    uint32_t dist_left_m;
    uint32_t time_left_s;
    uint16_t eta_min_of_day;
    uint16_t speed_kmh_x10;
    uint32_t icon_hash;
    char     primary[NAV_PRIMARY_MAX + 1];
    char     secondary[NAV_SECONDARY_MAX + 1];
} nav_state_t;

typedef enum {
    NAV_URGENCY_FAR,    /* cruising, nothing to do soon            */
    NAV_URGENCY_NEAR,   /* turn coming up: get ready               */
    NAV_URGENCY_NOW,    /* turn here                               */
} nav_urgency_t;

void nav_state_clear(nav_state_t *s);
bool nav_decode(const uint8_t *buf, size_t len, nav_state_t *out);
nav_urgency_t nav_urgency(const nav_state_t *s);

/* Text formatting. `num` gets the figure, `unit` the unit, e.g. "1.2" / "km". */
void nav_fmt_distance(uint32_t meters, bool imperial, char *num, size_t num_len, char *unit, size_t unit_len);
void nav_fmt_duration(uint32_t seconds, char *num, size_t num_len, char *unit, size_t unit_len);
void nav_fmt_clock(int hour, int minute, bool use_24h, char *out, size_t out_len);
int  nav_speed_display(uint16_t speed_kmh_x10, bool imperial);   /* -1 when unknown */

#ifdef __cplusplus
}
#endif
