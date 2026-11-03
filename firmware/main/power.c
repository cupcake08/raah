#include "power.h"

#include <stdatomic.h>

#include "app_events.h"
#include "button_gpio.h"
#include "driver/gpio.h"
#include "driver/rtc_io.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_log.h"
#include "esp_pm.h"
#include "esp_sleep.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "iot_button.h"
#include "sdkconfig.h"

static const char *TAG = "power";

#ifndef CONFIG_RAAH_LCD_BL_ACTIVE_LOW
#define CONFIG_RAAH_LCD_BL_ACTIVE_LOW 0
#endif

#define BATT_PERIOD_MS 30000
#define NO_BATT_MV     2500    /* below this nothing is on the divider: running from USB, no cell fitted */
#define LDR_PERIOD_MS  5000

static adc_oneshot_unit_handle_t adc;
static adc_cali_handle_t cali_batt, cali_ldr;
static adc_channel_t ch_batt, ch_ldr;
static bool has_batt, has_ldr;
static atomic_int ambient = -1;

/* Resting LiPo voltage → state of charge. */
static const struct {
    uint16_t mv;
    uint8_t pct;
} lipo[] = {
    {4180, 100}, {4100, 92}, {4020, 84}, {3950, 75}, {3880, 64}, {3830, 54}, {3790, 45},
    {3760, 36},  {3730, 27}, {3700, 19}, {3660, 11}, {3600, 5},  {3450, 1},  {3300, 0},
};

static int lipo_pct(int mv)
{
    if (mv >= lipo[0].mv) return 100;
    for (size_t i = 1; i < sizeof(lipo) / sizeof(lipo[0]); i++) {
        if (mv >= lipo[i].mv) {
            int dv = lipo[i - 1].mv - lipo[i].mv, dp = lipo[i - 1].pct - lipo[i].pct;
            return lipo[i].pct + (mv - lipo[i].mv) * dp / dv;
        }
    }
    return 0;
}

static bool adc_channel(int gpio, adc_channel_t *ch, adc_cali_handle_t *cali)
{
    if (gpio < 0) return false;
    adc_unit_t unit;
    if (adc_oneshot_io_to_channel(gpio, &unit, ch) != ESP_OK || unit != ADC_UNIT_1) {
        ESP_LOGE(TAG, "GPIO%d is not an ADC1 pin", gpio);
        return false;
    }
    if (!adc) {
        adc_oneshot_unit_init_cfg_t u = {.unit_id = ADC_UNIT_1};
        ESP_ERROR_CHECK(adc_oneshot_new_unit(&u, &adc));
    }
    adc_oneshot_chan_cfg_t c = {.atten = ADC_ATTEN_DB_12, .bitwidth = ADC_BITWIDTH_DEFAULT};
    ESP_ERROR_CHECK(adc_oneshot_config_channel(adc, *ch, &c));
    adc_cali_curve_fitting_config_t cc = {
        .unit_id = ADC_UNIT_1, .chan = *ch, .atten = ADC_ATTEN_DB_12, .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    if (adc_cali_create_scheme_curve_fitting(&cc, cali) != ESP_OK) *cali = NULL;
    return true;
}

static int read_mv(adc_channel_t ch, adc_cali_handle_t cali)
{
    int sum = 0, n = 0;
    for (int i = 0; i < 16; i++) {
        int v;
        if (cali ? adc_oneshot_get_calibrated_result(adc, cali, ch, &v) == ESP_OK
                 : adc_oneshot_read(adc, ch, &v) == ESP_OK) {
            sum += v;
            n++;
        }
    }
    return n ? sum / n : -1;
}

static bool charging(void)
{
#if CONFIG_RAAH_PIN_CHARGING >= 0
    return gpio_get_level(CONFIG_RAAH_PIN_CHARGING) == 0;
#else
    return false;
#endif
}

static void sampler_task(void *arg)
{
    int ema_mv = -1;
    TickType_t last_batt = 0;
    for (bool first = true;; first = false) {
        if (has_ldr) {
            int mv = read_mv(ch_ldr, cali_ldr);
            if (mv >= 0) atomic_store(&ambient, mv >= 3000 ? 100 : mv * 100 / 3000);
        }
        if (has_batt && (first || xTaskGetTickCount() - last_batt >= pdMS_TO_TICKS(BATT_PERIOD_MS))) {
            last_batt = xTaskGetTickCount();
            int mv = read_mv(ch_batt, cali_batt);
            if (mv >= 0) {
                mv = mv * CONFIG_RAAH_BATT_DIVIDER_X100 / 100;
                /* A protected LiPo never reads this low while the chip runs, so this is "no battery":
                 * reported as unknown, which also keeps the low-battery shutdown out of it. */
                if (mv < NO_BATT_MV) {
                    ema_mv = -1;
                    app_post(&(app_event_t){.type = APP_EV_BATTERY, .battery = {-1, 0, charging()}});
                } else {
                    ema_mv = ema_mv < 0 ? mv : (ema_mv * 3 + mv) / 4;
                    app_post(&(app_event_t){.type = APP_EV_BATTERY,
                                            .battery = {lipo_pct(ema_mv), (uint16_t)ema_mv, charging()}});
                }
            }
        }
        vTaskDelay(pdMS_TO_TICKS(has_ldr ? LDR_PERIOD_MS : BATT_PERIOD_MS));
    }
}

static void button_cb(void *btn, void *usr)
{
    app_post(&(app_event_t){.type = APP_EV_BUTTON, .button = (btn_action_t)(uintptr_t)usr});
}

static void button_init(void)
{
    gpio_hold_dis(CONFIG_RAAH_PIN_BUTTON);
    button_config_t cfg = {.long_press_time = 2000};
    button_gpio_config_t gcfg = {
        .gpio_num = CONFIG_RAAH_PIN_BUTTON,
        .active_level = 0,
        .enable_power_save = true,
    };
    button_handle_t btn;
    ESP_ERROR_CHECK(iot_button_new_gpio_device(&cfg, &gcfg, &btn));
    iot_button_register_cb(btn, BUTTON_SINGLE_CLICK, NULL, button_cb, (void *)BTN_CLICK);
    iot_button_register_cb(btn, BUTTON_DOUBLE_CLICK, NULL, button_cb, (void *)BTN_DOUBLE);
    iot_button_register_cb(btn, BUTTON_LONG_PRESS_START, NULL, button_cb, (void *)BTN_LONG);
}

void power_init(void)
{
    gpio_hold_dis(CONFIG_RAAH_PIN_LCD_BL);
    gpio_deep_sleep_hold_dis();

#if CONFIG_RAAH_LIGHT_SLEEP
    esp_pm_config_t pm = {.max_freq_mhz = 160, .min_freq_mhz = 40, .light_sleep_enable = true};
    ESP_ERROR_CHECK(esp_pm_configure(&pm));
#endif

#if CONFIG_RAAH_PIN_CHARGING >= 0
    gpio_config_t io = {
        .pin_bit_mask = 1ULL << CONFIG_RAAH_PIN_CHARGING,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
    };
    gpio_config(&io);
#endif
    has_batt = adc_channel(CONFIG_RAAH_PIN_BATT_ADC, &ch_batt, &cali_batt);
    has_ldr = adc_channel(CONFIG_RAAH_PIN_LDR_ADC, &ch_ldr, &cali_ldr);
    if (has_batt || has_ldr) xTaskCreate(sampler_task, "sampler", 3072, NULL, 2, NULL);

    button_init();
    ESP_LOGI(TAG, "wake cause %d, battery sense %s, light sensor %s", esp_sleep_get_wakeup_causes(),
             has_batt ? "on" : "off", has_ldr ? "on" : "off");
}

int power_ambient_pct(void)
{
    return atomic_load(&ambient);
}

void power_deep_sleep(void)
{
    /* Keep the backlight driver off while the GPIO matrix is powered down. */
    gpio_set_direction(CONFIG_RAAH_PIN_LCD_BL, GPIO_MODE_OUTPUT);
    gpio_set_level(CONFIG_RAAH_PIN_LCD_BL, CONFIG_RAAH_LCD_BL_ACTIVE_LOW);
    gpio_hold_en(CONFIG_RAAH_PIN_LCD_BL);
    gpio_deep_sleep_hold_en();

    /* Don't wake straight back up while the long press is still held. */
    while (gpio_get_level(CONFIG_RAAH_PIN_BUTTON) == 0) vTaskDelay(pdMS_TO_TICKS(20));
    vTaskDelay(pdMS_TO_TICKS(50));

    rtc_gpio_pullup_en(CONFIG_RAAH_PIN_BUTTON);
    rtc_gpio_pulldown_dis(CONFIG_RAAH_PIN_BUTTON);
    esp_sleep_enable_ext1_wakeup_io(1ULL << CONFIG_RAAH_PIN_BUTTON, ESP_EXT1_WAKEUP_ANY_LOW);
    ESP_LOGI(TAG, "deep sleep");
    esp_deep_sleep_start();
}
