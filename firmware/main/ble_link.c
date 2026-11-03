#include "ble_link.h"

#include <string.h>

#include "app_events.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "map_proto.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char *TAG = "ble";

#define DEVICE_NAME  "Raah"
#define FAST_ADV_MS  30000   /* then drop to a 1 s interval to save power */
#define ADV_RETRY_MS 500     /* a start refused while a dead link is torn down is retried this soon */

/* 8c5a1f40-6b2e-4d2a-9b7e-5f3a2c1d0eXX, little-endian. */
#define NAV_UUID(x) \
    BLE_UUID128_INIT(x, 0x0e, 0x1d, 0x2c, 0x3a, 0x5f, 0x7e, 0x9b, 0x2a, 0x4d, 0x2e, 0x6b, 0x40, 0x1f, 0x5a, 0x8c)

static const ble_uuid128_t uuid_svc = NAV_UUID(0x00);
static const ble_uuid128_t uuid_state = NAV_UUID(0x01);
static const ble_uuid128_t uuid_icon = NAV_UUID(0x02);
static const ble_uuid128_t uuid_ctrl = NAV_UUID(0x03);
static const ble_uuid128_t uuid_status = NAV_UUID(0x04);
static const ble_uuid128_t uuid_map = NAV_UUID(0x05);

enum { CHR_STATE, CHR_ICON, CHR_CTRL, CHR_STATUS, CHR_MAP };

static uint16_t conn = BLE_HS_CONN_HANDLE_NONE;
static uint16_t status_handle;
static bool status_subscribed;
static uint8_t own_addr_type;
static uint8_t status_val[NAV_STATUS_LEN] = {NAV_PROTO_VERSION, 0xFF, 0, 0, 0, NAV_ICON_PX, 0, 0};
static esp_timer_handle_t adv_timer;
static bool adv_next_slow = true;   /* what the timer starts: slow after the fast window, else a retry */

/* Icons are assembled in the host task, then handed to the UI task through two slots. */
static uint8_t asm_buf[NAV_ICON_BYTES];
static uint32_t asm_hash, asm_got;
static uint8_t ready_buf[2][NAV_ICON_BYTES];
static uint32_t ready_hash[2];
static uint8_t ready_next;
static SemaphoreHandle_t icon_lock;

/*
 * Maps are assembled in the host task into whichever buffer the UI is not drawing.
 * A complete map waits in `map_ready` until the UI adopts it (on the first pose in its
 * frame). A new map that starts meanwhile replaces it: the phone has moved on (a new
 * route, or it gave up waiting for STATUS), so no pose for the waiting one will come.
 */
static map_buf_t map_bufs[2];
static map_rx_t map_rx = {.buf = &map_bufs[0]};
static int map_front = -1, map_ready = -1;
static SemaphoreHandle_t map_lock;

static void advertise(bool slow);
static void notify_status(void);

static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | p[1] << 8); }
static uint32_t rd32(const uint8_t *p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }

static void icon_chunk(const uint8_t *b, uint16_t len)
{
    if (len < 7) return;
    uint32_t hash = rd32(b);
    uint16_t off = rd16(b + 4), n = len - 6;
    if (!hash || off + n > NAV_ICON_BYTES) return;
    if (hash != asm_hash || off == 0) {
        asm_hash = hash;
        asm_got = 0;
    }
    memcpy(asm_buf + off, b + 6, n);
    asm_got += n;
    if (asm_got < NAV_ICON_BYTES) return;

    xSemaphoreTake(icon_lock, portMAX_DELAY);
    uint8_t slot = ready_next;
    ready_next ^= 1;
    memcpy(ready_buf[slot], asm_buf, NAV_ICON_BYTES);
    ready_hash[slot] = hash;
    xSemaphoreGive(icon_lock);
    asm_hash = 0;
    asm_got = 0;
    app_post(&(app_event_t){.type = APP_EV_ICON, .icon_slot = slot});
}

static void set_status_map_id(uint16_t id)
{
    status_val[6] = (uint8_t)id;
    status_val[7] = (uint8_t)(id >> 8);
}

static void map_write(const uint8_t *b, uint16_t len)
{
    map_pose_t pose;
    xSemaphoreTake(map_lock, portMAX_DELAY);
    if (len && b[0] == MAP_MSG_BEGIN && !map_rx.buf && map_ready >= 0) {
        ESP_LOGI(TAG, "map %u superseded before it was shown", map_bufs[map_ready].id);
        map_rx = (map_rx_t){.buf = &map_bufs[map_ready]};
        map_ready = -1;
        set_status_map_id(0);
    }
    map_rx_result_t r = map_rx_feed(&map_rx, b, len, &pose);
    if (r == MAP_RX_DONE) {
        map_ready = map_rx.buf == &map_bufs[0] ? 0 : 1;
        map_rx.buf = NULL;
        set_status_map_id(map_bufs[map_ready].id);
    }
    xSemaphoreGive(map_lock);

    switch (r) {
    case MAP_RX_DONE:
        ESP_LOGI(TAG, "map %u: %u pts, %u roads, %u route pts", map_bufs[map_ready].id, map_bufs[map_ready].npts,
                 map_bufs[map_ready].nlines, map_bufs[map_ready].nroute);
        notify_status();
        break;
    case MAP_RX_POSE:
        app_post(&(app_event_t){.type = APP_EV_POSE, .pose = pose});
        break;
    case MAP_RX_CLEAR:
        app_post(&(app_event_t){.type = APP_EV_MAP_CLEAR});
        break;
    case MAP_RX_BAD:
        ESP_LOGW(TAG, "map message 0x%02x dropped", b[0]);
        break;
    default:
        break;
    }
}

static void map_reset(void)
{
    xSemaphoreTake(map_lock, portMAX_DELAY);
    map_ready = -1;
    map_rx = (map_rx_t){.buf = &map_bufs[map_front == 0 ? 1 : 0]};
    set_status_map_id(0);
    xSemaphoreGive(map_lock);
}

static void ctrl_write(const uint8_t *b, uint16_t len)
{
    if (len >= 7 && b[0] == NAV_CTRL_TIME) {
        app_post(&(app_event_t){.type = APP_EV_TIME, .time = {rd32(b + 1), (int16_t)rd16(b + 5)}});
    } else if (len >= 5 && b[0] == NAV_CTRL_SETTINGS) {
        app_post(&(app_event_t){.type = APP_EV_SETTINGS,
                                .settings = {.imperial = b[1], .use_24h = b[2], .bl_mode = b[3], .max_brightness = b[4]}});
    }
}

static int gatt_access(uint16_t conn_handle, uint16_t attr_handle, struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        return os_mbuf_append(ctxt->om, status_val, sizeof(status_val)) ? BLE_ATT_ERR_INSUFFICIENT_RES : 0;
    }
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) return BLE_ATT_ERR_UNLIKELY;

    uint8_t buf[256];
    uint16_t len;
    if (ble_hs_mbuf_to_flat(ctxt->om, buf, sizeof(buf), &len)) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;

    switch ((uintptr_t)arg) {
    case CHR_STATE: {
        app_event_t ev = {.type = APP_EV_NAV};
        if (!nav_decode(buf, len, &ev.nav)) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        app_post(&ev);
        return 0;
    }
    case CHR_ICON:
        icon_chunk(buf, len);
        return 0;
    case CHR_CTRL:
        ctrl_write(buf, len);
        return 0;
    case CHR_MAP:
        map_write(buf, len);
        return 0;
    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

static const struct ble_gatt_svc_def services[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &uuid_svc.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {.uuid = &uuid_state.u, .access_cb = gatt_access, .arg = (void *)CHR_STATE,
             .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP},
            {.uuid = &uuid_icon.u, .access_cb = gatt_access, .arg = (void *)CHR_ICON,
             .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP},
            {.uuid = &uuid_ctrl.u, .access_cb = gatt_access, .arg = (void *)CHR_CTRL, .flags = BLE_GATT_CHR_F_WRITE},
            {.uuid = &uuid_status.u, .access_cb = gatt_access, .arg = (void *)CHR_STATUS,
             .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY, .val_handle = &status_handle},
            {.uuid = &uuid_map.u, .access_cb = gatt_access, .arg = (void *)CHR_MAP,
             .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP},
            {0},
        },
    },
    {0},
};

static void notify_status(void)
{
    if (conn == BLE_HS_CONN_HANDLE_NONE || !status_subscribed) return;
    struct os_mbuf *om = ble_hs_mbuf_from_flat(status_val, sizeof(status_val));
    if (om) ble_gatts_notify_custom(conn, status_handle, om);
}

static int gap_event(struct ble_gap_event *e, void *arg)
{
    switch (e->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (e->connect.status != 0) {
            advertise(false);
            break;
        }
        conn = e->connect.conn_handle;
        esp_timer_stop(adv_timer);
        /* 30-50 ms interval, skip up to 4 idle events: low duty cycle, still snappy for icon bursts. */
        struct ble_gap_upd_params p = {
            .itvl_min = 24, .itvl_max = 40, .latency = 4, .supervision_timeout = 500,
        };
        ble_gap_update_params(conn, &p);
        ESP_LOGI(TAG, "connected");
        app_post(&(app_event_t){.type = APP_EV_CONNECTED});
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnected (reason 0x%x)", e->disconnect.reason);
        conn = BLE_HS_CONN_HANDLE_NONE;
        status_subscribed = false;
        asm_hash = 0;
        asm_got = 0;
        map_reset();
        app_post(&(app_event_t){.type = APP_EV_DISCONNECTED});
        advertise(false);
        break;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        if (conn == BLE_HS_CONN_HANDLE_NONE) advertise(true);
        break;
    case BLE_GAP_EVENT_SUBSCRIBE:
        if (e->subscribe.attr_handle == status_handle) {
            status_subscribed = e->subscribe.cur_notify;
            notify_status();
        }
        break;
    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "mtu %d", e->mtu.value);
        break;
    default:
        break;
    }
    return 0;
}

static void advertise(bool slow)
{
    ble_gap_adv_stop();

    struct ble_hs_adv_fields f = {0};
    f.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    f.uuids128 = &uuid_svc;
    f.num_uuids128 = 1;
    f.uuids128_is_complete = 1;
    ble_gap_adv_set_fields(&f);

    struct ble_hs_adv_fields r = {0};
    r.name = (const uint8_t *)DEVICE_NAME;
    r.name_len = sizeof(DEVICE_NAME) - 1;
    r.name_is_complete = 1;
    ble_gap_adv_rsp_set_fields(&r);

    struct ble_gap_adv_params p = {
        .conn_mode = BLE_GAP_CONN_MODE_UND,
        .disc_mode = BLE_GAP_DISC_MODE_GEN,
        .itvl_min = slow ? BLE_GAP_ADV_ITVL_MS(1000) : BLE_GAP_ADV_ITVL_MS(100),
        .itvl_max = slow ? BLE_GAP_ADV_ITVL_MS(1200) : BLE_GAP_ADV_ITVL_MS(150),
    };
    /* A connection that fails as it opens (e.g. the phone walks out of range mid-handshake) is still
     * in the host's table for a moment, and the start is refused with ENOMEM. Without a retry the
     * device would stay invisible until the fast window ended. */
    int rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &p, gap_event, NULL);
    if (rc) {
        ESP_LOGW(TAG, "adv start failed (%d), retrying", rc);
        adv_next_slow = slow;
        esp_timer_stop(adv_timer);
        esp_timer_start_once(adv_timer, ADV_RETRY_MS * 1000ull);
    } else if (!slow) {
        adv_next_slow = true;
        esp_timer_stop(adv_timer);
        esp_timer_start_once(adv_timer, FAST_ADV_MS * 1000ull);
    }
}

static void adv_timeout(void *arg)
{
    if (conn == BLE_HS_CONN_HANDLE_NONE) advertise(adv_next_slow);
}

static void on_sync(void)
{
    ble_hs_util_ensure_addr(0);
    ble_hs_id_infer_auto(0, &own_addr_type);
    ble_gap_set_prefered_default_le_phy(BLE_GAP_LE_PHY_2M_MASK | BLE_GAP_LE_PHY_1M_MASK,
                                        BLE_GAP_LE_PHY_2M_MASK | BLE_GAP_LE_PHY_1M_MASK);
    advertise(false);
}

static void on_reset(int reason)
{
    ESP_LOGW(TAG, "host reset, reason %d", reason);
}

static void host_task(void *param)
{
    nimble_port_run();
    nimble_port_freertos_deinit();
}

void ble_link_init(void)
{
    icon_lock = xSemaphoreCreateMutex();
    map_lock = xSemaphoreCreateMutex();
    const esp_timer_create_args_t targs = {.callback = adv_timeout, .name = "adv"};
    ESP_ERROR_CHECK(esp_timer_create(&targs, &adv_timer));

    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.reset_cb = on_reset;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    ESP_ERROR_CHECK(ble_gatts_count_cfg(services));
    ESP_ERROR_CHECK(ble_gatts_add_svcs(services));
    ble_svc_gap_device_name_set(DEVICE_NAME);
    ble_att_set_preferred_mtu(247);
    nimble_port_freertos_init(host_task);
}

void ble_link_icon_apply(uint8_t slot, void (*fn)(uint32_t hash, const uint8_t *a8))
{
    if (slot > 1) return;
    xSemaphoreTake(icon_lock, portMAX_DELAY);
    fn(ready_hash[slot], ready_buf[slot]);
    xSemaphoreGive(icon_lock);
}

void ble_link_set_status(int pct, uint16_t mv, bool charging)
{
    uint8_t v[6] = {NAV_PROTO_VERSION, pct < 0 ? 0xFF : (uint8_t)pct, (uint8_t)mv, (uint8_t)(mv >> 8), charging,
                    NAV_ICON_PX};
    if (!memcmp(v, status_val, sizeof(v))) return;
    memcpy(status_val, v, sizeof(v));
    notify_status();
}

bool ble_link_connected(void)
{
    return conn != BLE_HS_CONN_HANDLE_NONE;
}

const map_buf_t *ble_link_map_adopt(uint16_t id)
{
    const map_buf_t *m = NULL;
    xSemaphoreTake(map_lock, portMAX_DELAY);
    if (map_ready >= 0 && map_bufs[map_ready].id == id) {
        map_front = map_ready;
        map_ready = -1;
        map_rx = (map_rx_t){.buf = &map_bufs[map_front ^ 1]};
        m = &map_bufs[map_front];
    }
    xSemaphoreGive(map_lock);
    return m;
}
