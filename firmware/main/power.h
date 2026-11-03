#pragma once

#include <stdbool.h>

/* Enables DFS + automatic light sleep, starts the battery/ambient sampler and the button. */
void power_init(void);

/* Ambient light 0-100, or -1 when no sensor is fitted. */
int power_ambient_pct(void);

/* Holds the backlight off, arms the button as wake source and enters deep sleep. A key still held about
 * 6 s after the screen went dark restarts into USB download mode instead. */
void power_deep_sleep(void) __attribute__((noreturn));
