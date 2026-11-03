set(srcs "main.c" "display.c" "ble_link.c" "power.c")
set(incs ".")
if(CONFIG_RAAH_BENCH)
    list(APPEND srcs "../../bench/ui_bench.c")
    list(APPEND incs "../../bench" "../../sim")
endif()

idf_component_register(SRCS ${srcs}
                       INCLUDE_DIRS ${incs}
                       REQUIRES ui lvgl__lvgl esp_lcd esp_driver_spi esp_driver_ledc esp_driver_gpio
                                esp_adc esp_pm esp_timer nvs_flash bt espressif__button espressif__esp_lcd_ili9341)
