/*
 * MAP characteristic: road geometry and rider pose for the map screen.
 * Keep in sync with android/.../proto/MapProtocol.kt.
 *
 * One message per ATT write (little-endian, at most 244 bytes):
 *   0x10 BEGIN  u16 id | u16 n_pts | u16 n_lines | u16 n_route
 *   0x11 PTS    u16 id | u16 first | n × (i16 x, i16 y)        metres east/north of the map's anchor
 *   0x12 LINES  u16 id | u16 first | n × (u8 cls, u8 n, u16 first_pt)
 *   0x13 ROUTE  u16 id | u16 first | n × (i16 x, i16 y)
 *   0x14 COMMIT u16 id
 *   0x15 CLEAR
 *   0x20 POSE   u16 id | i32 x_dm | i32 y_dm | u16 heading_deg_x10 | u16 m_per_px_x100
 *
 * Chunks must arrive in order. Every map has its own anchor, so a pose only
 * applies to the map with the same id. Once a map is committed, the device
 * reports its id in STATUS. The phone then sends poses in that map's frame,
 * and the device swaps to the new map when the first of those poses arrives.
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "ui_map.h"

#ifdef __cplusplus
extern "C" {
#endif

#define MAP_MSG_BEGIN   0x10
#define MAP_MSG_PTS     0x11
#define MAP_MSG_LINES   0x12
#define MAP_MSG_ROUTE   0x13
#define MAP_MSG_COMMIT  0x14
#define MAP_MSG_CLEAR   0x15
#define MAP_MSG_POSE    0x20

#define MAP_MAX_PTS     3072
#define MAP_MAX_LINES   1024
#define MAP_MAX_ROUTE   512
_Static_assert(MAP_MAX_LINES <= UI_MAP_MAX_LINES, "the renderer must be able to draw every line a map can hold");

typedef struct {
    uint16_t id;
    uint16_t npts, nlines, nroute;
    map_pt_t pts[MAP_MAX_PTS];
    map_line_t lines[MAP_MAX_LINES];
    map_pt_t route[MAP_MAX_ROUTE];
} map_buf_t;

typedef struct {
    uint16_t id;
    float x, y;         /* metres in the map's frame */
    float heading;      /* degrees clockwise from north */
    float m_per_px;
} map_pose_t;

/* Assembles one map at a time into `buf`. A NULL buf drops map transfers. */
typedef struct {
    map_buf_t *buf;
    uint16_t got_pts, got_lines, got_route;
    bool open;
} map_rx_t;

typedef enum {
    MAP_RX_NONE,    /* consumed, nothing to act on yet       */
    MAP_RX_DONE,    /* rx->buf holds a complete, valid map   */
    MAP_RX_CLEAR,   /* hide the map                          */
    MAP_RX_POSE,    /* *pose was filled in                   */
    MAP_RX_BAD,     /* malformed or out of order: dropped    */
} map_rx_result_t;

map_rx_result_t map_rx_feed(map_rx_t *rx, const uint8_t *b, size_t len, map_pose_t *pose);
map_data_t map_buf_data(const map_buf_t *b);

#ifdef __cplusplus
}
#endif
