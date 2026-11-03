/*
 * Map-screen benchmark shared by the host simulator (`raah_sim - bench`) and the ESP32-S3 bench app
 * (bench/esp), so host numbers and target numbers come from the same scenarios and the same UI code.
 */
#pragma once

#include <stdint.h>

typedef struct {
    uint64_t (*now)(void);      /* monotonic clock */
    uint32_t per_us;            /* `now` ticks per microsecond */
    const uint64_t *px;         /* pixels flushed so far (the caller's flush callback counts them) */
    const uint64_t *flushes;    /* flush calls so far */
    void (*run)(uint32_t ms);   /* advances LVGL time by ms, running timers */
    void (*yield)(void);        /* optional, between frames and outside the timing (lets a watchdog run) */
} ui_bench_env_t;

/* Builds the map screen from the demo map and prints per-frame work for each scenario. */
void ui_bench_run(const ui_bench_env_t *env);
