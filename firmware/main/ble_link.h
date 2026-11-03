/* BLE peripheral exposing the Raah GATT service (see ui/nav_model.h for the wire format). */
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "map_proto.h"

void ble_link_init(void);

/* Runs fn on the completed icon bitmap of an APP_EV_ICON slot, under the slot lock. */
void ble_link_icon_apply(uint8_t slot, void (*fn)(uint32_t hash, const uint8_t *a8));

/* Updates the STATUS characteristic and notifies the phone if it subscribed. */
void ble_link_set_status(int battery_pct, uint16_t battery_mv, bool charging);

bool ble_link_connected(void);

/*
 * Called by the UI task when a pose for map `id` arrives. Returns that map if it
 * has been received and not yet adopted; the UI must switch to it before it next
 * draws, because the previous map's buffer is then reused for the next transfer.
 */
const map_buf_t *ble_link_map_adopt(uint16_t id);
