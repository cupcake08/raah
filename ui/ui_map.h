/*
 * Heading-up vector map: roads by class, the route ahead and the rider chevron,
 * drawn with LVGL's anti-aliased lines. Geometry arrives from the phone in
 * metres relative to an anchor, so the device only rotates, scales and draws.
 */
#pragma once

#include <stdint.h>

#include "lvgl.h"

typedef struct {
    int16_t x, y;   /* metres east / north of the map anchor */
} map_pt_t;

typedef enum {
    MAP_CLASS_SERVICE,  /* service roads, parking aisles  */
    MAP_CLASS_MINOR,    /* residential / unclassified     */
    MAP_CLASS_MID,      /* secondary / tertiary           */
    MAP_CLASS_MAJOR,    /* primary / trunk / motorway     */
    MAP_CLASS_COUNT
} map_class_t;

typedef struct {
    uint8_t cls;
    uint8_t n;          /* points in this polyline */
    uint16_t first;     /* index into map_data_t.pts */
} map_line_t;

/* Most polylines the renderer draws; map_proto.h's MAP_MAX_LINES stays within it. */
#define UI_MAP_MAX_LINES 1024

typedef struct {
    const map_pt_t *pts;
    const map_line_t *lines;
    uint16_t nlines;
    const map_pt_t *route;
    uint16_t nroute;
} map_data_t;

typedef struct {
    float x, y;         /* rider position, metres in the anchor frame */
    float heading;      /* degrees clockwise from north; the map rotates so this points up */
    float m_per_px;     /* zoom */
    int32_t rider_y;    /* rider chevron row, pixels from the top of the widget */
} map_view_t;

lv_obj_t *ui_map_create(lv_obj_t *parent);
/* The data is referenced, not copied: keep it alive while the map shows it. */
void ui_map_set_data(lv_obj_t *map, const map_data_t *data);
void ui_map_set_view(lv_obj_t *map, const map_view_t *view);
/* Ink for the route ahead (follows the turn urgency colour). */
void ui_map_set_route_color(lv_obj_t *map, lv_color_t c);

/* An opaque box with rounded corners laid over the map (the turn card). Map changes leave its inside
 * alone; whoever changes the box repaints it. */
void ui_map_set_cover(lv_obj_t *map, lv_obj_t *cover, int32_t radius);

/* Rows at the top of the map hidden under an opaque band (the status bar): map changes leave them alone. */
void ui_map_set_top_cover(lv_obj_t *map, int32_t rows);

#ifdef UI_MAP_STATS
/* Work counters for the simulator's benchmark; compiled out of the firmware. */
typedef struct {
    uint32_t draws;         /* draw callbacks (one per refreshed strip) */
    uint32_t transforms;    /* points rotated into screen space */
    uint32_t polylines;     /* polylines handed to LVGL */
    uint32_t segments;      /* line segments in them (LVGL rasterises each one) */
} ui_map_stats_t;
extern ui_map_stats_t ui_map_stats;

/* Switches the benchmark flips to see what each part of the map costs. */
typedef struct {
    bool no_caps;           /* square line ends instead of round */
    bool no_roads;
    bool no_route;
} ui_map_knobs_t;
extern ui_map_knobs_t ui_map_knobs;
#endif
