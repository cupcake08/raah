/* Messages from the BLE and power tasks to the UI task, which owns LVGL. */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "map_proto.h"
#include "nav_model.h"

typedef enum {
    APP_EV_CONNECTED,
    APP_EV_DISCONNECTED,
    APP_EV_NAV,
    APP_EV_ICON,        /* icon_slot is ready in ble_link_icon() */
    APP_EV_TIME,
    APP_EV_SETTINGS,
    APP_EV_BUTTON,
    APP_EV_BATTERY,
    APP_EV_POSE,        /* rider pose in the frame of map pose.id */
    APP_EV_MAP_CLEAR,
} app_ev_type_t;

typedef enum {
    BTN_CLICK,
    BTN_DOUBLE,
    BTN_LONG,
} btn_action_t;

typedef enum {
    BL_MODE_ALWAYS = 0,     /* always at full brightness              */
    BL_MODE_SMART_DIM,      /* full near turns, dimmed while cruising */
    BL_MODE_SMART_OFF,      /* full near turns, off while cruising    */
    BL_MODE_COUNT
} bl_mode_t;

typedef struct {
    uint8_t imperial;
    uint8_t use_24h;
    uint8_t bl_mode;
    uint8_t max_brightness;     /* percent */
} app_settings_t;

typedef struct {
    app_ev_type_t type;
    union {
        nav_state_t nav;
        uint8_t icon_slot;
        app_settings_t settings;
        btn_action_t button;
        map_pose_t pose;
        struct {
            uint32_t utc;
            int16_t offset_min;
        } time;
        struct {
            int pct;
            uint16_t mv;
            bool charging;
        } battery;
    };
} app_event_t;

/* Post from any task (never from an ISR). Drops the event if the queue is full. */
void app_post(const app_event_t *ev);
