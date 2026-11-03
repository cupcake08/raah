#include "nav_model.h"

#include <stdio.h>
#include <string.h>

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | p[1] << 8); }
static uint32_t rd32(const uint8_t *p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }

void nav_state_clear(nav_state_t *s)
{
    memset(s, 0, sizeof(*s));
    s->dist_to_turn_m = NAV_U32_UNKNOWN;
    s->dist_left_m = NAV_U32_UNKNOWN;
    s->time_left_s = NAV_U32_UNKNOWN;
    s->eta_min_of_day = NAV_U16_UNKNOWN;
    s->speed_kmh_x10 = NAV_U16_UNKNOWN;
}

/* Copies a length-prefixed UTF-8 string, cutting on a code point boundary and dropping control chars. */
static bool read_str(const uint8_t **p, const uint8_t *end, char *out, size_t cap)
{
    if (*p >= end) {
        out[0] = '\0';
        return true;
    }
    size_t len = **p;
    (*p)++;
    if ((size_t)(end - *p) < len) return false;
    size_t n = len < cap - 1 ? len : cap - 1;
    while (n > 0 && n < len && ((*p)[n] & 0xC0) == 0x80) n--;
    size_t o = 0;
    for (size_t i = 0; i < n; i++) {
        uint8_t c = (*p)[i];
        out[o++] = (c < 0x20 || c == 0x7F) ? ' ' : (char)c;
    }
    out[o] = '\0';
    *p += len;
    return true;
}

bool nav_decode(const uint8_t *buf, size_t len, nav_state_t *out)
{
    if (len < NAV_HDR_LEN || buf[0] != NAV_PROTO_VERSION) return false;
    nav_state_t s;
    nav_state_clear(&s);
    s.flags = buf[1];
    s.maneuver = buf[2] < MANEUVER_COUNT ? buf[2] : MANEUVER_UNKNOWN;
    s.exit_num = buf[3];
    s.dist_to_turn_m = rd32(buf + 4);
    s.dist_left_m = rd32(buf + 8);
    s.time_left_s = rd32(buf + 12);
    s.eta_min_of_day = rd16(buf + 16);
    s.speed_kmh_x10 = rd16(buf + 18);
    s.icon_hash = rd32(buf + 20);

    const uint8_t *p = buf + NAV_HDR_LEN, *end = buf + len;
    if (!read_str(&p, end, s.primary, sizeof(s.primary))) return false;
    if (!read_str(&p, end, s.secondary, sizeof(s.secondary))) return false;
    *out = s;
    return true;
}

nav_urgency_t nav_urgency(const nav_state_t *s)
{
    if (!(s->flags & NAV_F_NAVIGATING) || (s->flags & (NAV_F_REROUTING | NAV_F_ARRIVED)) ||
        s->dist_to_turn_m == NAV_U32_UNKNOWN) {
        return NAV_URGENCY_FAR;
    }
    /* Distance thresholds scale with speed: d / v <= t  <=>  d * 36 <= t * kmh_x10. */
    uint64_t d36 = (uint64_t)s->dist_to_turn_m * 36;
    uint32_t v = s->speed_kmh_x10 == NAV_U16_UNKNOWN ? 0 : s->speed_kmh_x10;
    if (s->dist_to_turn_m <= 30 || d36 <= 4ull * v) return NAV_URGENCY_NOW;
    if (s->dist_to_turn_m <= 200 || d36 <= 25ull * v) return NAV_URGENCY_NEAR;
    return NAV_URGENCY_FAR;
}

void nav_fmt_distance(uint32_t m, bool imperial, char *num, size_t num_len, char *unit, size_t unit_len)
{
    if (m == NAV_U32_UNKNOWN) {
        snprintf(num, num_len, "--");
        snprintf(unit, unit_len, "%s", imperial ? "mi" : "km");
        return;
    }
    if (imperial) {
        uint32_t ft = (uint32_t)((uint64_t)m * 3281 / 1000);
        if (ft < 528) {
            snprintf(num, num_len, "%lu", (unsigned long)(ft < 100 ? (ft + 2) / 5 * 5 : (ft + 5) / 10 * 10));
            snprintf(unit, unit_len, "ft");
            return;
        }
        uint32_t tenths = (uint32_t)(((uint64_t)m * 10 + 804) / 1609);
        if (tenths < 100) snprintf(num, num_len, "%lu.%lu", (unsigned long)(tenths / 10), (unsigned long)(tenths % 10));
        else snprintf(num, num_len, "%lu", (unsigned long)((tenths + 5) / 10));
        snprintf(unit, unit_len, "mi");
        return;
    }
    if (m < 995) {
        uint32_t v = m < 100 ? (m + 2) / 5 * 5 : (m + 5) / 10 * 10;
        snprintf(num, num_len, "%lu", (unsigned long)v);
        snprintf(unit, unit_len, "m");
        return;
    }
    uint32_t tenths = (m + 50) / 100;
    if (tenths < 100) snprintf(num, num_len, "%lu.%lu", (unsigned long)(tenths / 10), (unsigned long)(tenths % 10));
    else snprintf(num, num_len, "%lu", (unsigned long)((m + 500) / 1000));
    snprintf(unit, unit_len, "km");
}

void nav_fmt_duration(uint32_t s, char *num, size_t num_len, char *unit, size_t unit_len)
{
    if (s == NAV_U32_UNKNOWN) {
        snprintf(num, num_len, "--");
        snprintf(unit, unit_len, "min");
        return;
    }
    uint32_t mins = (s + 30) / 60;
    if (mins < 1) mins = 1;
    if (mins < 60) {
        snprintf(num, num_len, "%lu", (unsigned long)mins);
        snprintf(unit, unit_len, "min");
    } else {
        snprintf(num, num_len, "%lu:%02lu", (unsigned long)(mins / 60), (unsigned long)(mins % 60));
        snprintf(unit, unit_len, "h");
    }
}

void nav_fmt_clock(int hour, int minute, bool use_24h, char *out, size_t out_len)
{
    if (hour < 0) {
        snprintf(out, out_len, "--:--");
    } else if (use_24h) {
        snprintf(out, out_len, "%02d:%02d", hour, minute);
    } else {
        snprintf(out, out_len, "%d:%02d", hour % 12 ? hour % 12 : 12, minute);
    }
}

int nav_speed_display(uint16_t kmh_x10, bool imperial)
{
    if (kmh_x10 == NAV_U16_UNKNOWN) return -1;
    return imperial ? (int)(((uint32_t)kmh_x10 * 621 + 5000) / 10000) : (kmh_x10 + 5) / 10;
}
