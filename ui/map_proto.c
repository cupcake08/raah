#include "map_proto.h"

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | p[1] << 8); }
static uint32_t rd32(const uint8_t *p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }

static map_rx_result_t drop(map_rx_t *rx)
{
    rx->open = false;
    return MAP_RX_BAD;
}

/* Copies n 4-byte points into dst[first..], requiring first == *got. */
static bool take_pts(map_pt_t *dst, uint16_t cap, uint16_t *got, uint16_t first, const uint8_t *p, size_t n)
{
    if (first != *got || first + n > cap) return false;
    for (size_t i = 0; i < n; i++, p += 4) dst[first + i] = (map_pt_t){(int16_t)rd16(p), (int16_t)rd16(p + 2)};
    *got += (uint16_t)n;
    return true;
}

static bool valid(const map_rx_t *rx)
{
    const map_buf_t *m = rx->buf;
    if (rx->got_pts != m->npts || rx->got_lines != m->nlines || rx->got_route != m->nroute) return false;
    for (uint16_t i = 0; i < m->nlines; i++) {
        const map_line_t *l = &m->lines[i];
        if (l->cls >= MAP_CLASS_COUNT || l->n < 2 || l->first + l->n > m->npts) return false;
    }
    return true;
}

map_rx_result_t map_rx_feed(map_rx_t *rx, const uint8_t *b, size_t len, map_pose_t *pose)
{
    if (len < 1) return MAP_RX_BAD;
    uint8_t type = b[0];

    if (type == MAP_MSG_CLEAR) return MAP_RX_CLEAR;
    if (type == MAP_MSG_POSE) {
        if (len < 15) return MAP_RX_BAD;
        *pose = (map_pose_t){
            .id = rd16(b + 1),
            .x = (int32_t)rd32(b + 3) / 10.f,
            .y = (int32_t)rd32(b + 7) / 10.f,
            .heading = rd16(b + 11) / 10.f,
            .m_per_px = rd16(b + 13) / 100.f,
        };
        return pose->m_per_px > 0 ? MAP_RX_POSE : MAP_RX_BAD;
    }

    if (!rx->buf || len < 3) return MAP_RX_BAD;
    map_buf_t *m = rx->buf;
    uint16_t id = rd16(b + 1);

    if (type == MAP_MSG_BEGIN) {
        if (len < 9 || !id) return drop(rx);
        m->id = id;
        m->npts = rd16(b + 3);
        m->nlines = rd16(b + 5);
        m->nroute = rd16(b + 7);
        if (m->npts > MAP_MAX_PTS || m->nlines > MAP_MAX_LINES || m->nroute > MAP_MAX_ROUTE) return drop(rx);
        rx->got_pts = rx->got_lines = rx->got_route = 0;
        rx->open = true;
        return MAP_RX_NONE;
    }

    if (!rx->open || id != m->id) return MAP_RX_BAD;

    if (type == MAP_MSG_COMMIT) {
        rx->open = false;
        return valid(rx) ? MAP_RX_DONE : MAP_RX_BAD;
    }

    if (len < 5 || (len - 5) % 4) return drop(rx);
    uint16_t first = rd16(b + 3);
    const uint8_t *p = b + 5;
    size_t n = (len - 5) / 4;
    bool ok;
    switch (type) {
    case MAP_MSG_PTS:
        ok = take_pts(m->pts, m->npts, &rx->got_pts, first, p, n);
        break;
    case MAP_MSG_ROUTE:
        ok = take_pts(m->route, m->nroute, &rx->got_route, first, p, n);
        break;
    case MAP_MSG_LINES:
        ok = first == rx->got_lines && first + n <= m->nlines;
        for (size_t i = 0; ok && i < n; i++, p += 4)
            m->lines[first + i] = (map_line_t){.cls = p[0], .n = p[1], .first = rd16(p + 2)};
        if (ok) rx->got_lines += (uint16_t)n;
        break;
    default:
        ok = false;
        break;
    }
    return ok ? MAP_RX_NONE : drop(rx);
}

map_data_t map_buf_data(const map_buf_t *b)
{
    return (map_data_t){
        .pts = b->pts, .lines = b->lines, .nlines = b->nlines, .route = b->route, .nroute = b->nroute,
    };
}
