# Portable UI: an ESP-IDF component in the firmware, plain sources for the host simulator.
file(GLOB UI_SRCS "${CMAKE_CURRENT_LIST_DIR}/*.c" "${CMAKE_CURRENT_LIST_DIR}/fonts/*.c")

if(ESP_PLATFORM)
    idf_component_register(SRCS ${UI_SRCS}
                           INCLUDE_DIRS "."
                           REQUIRES lvgl__lvgl)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE LV_LVGL_H_INCLUDE_SIMPLE)
    if(CONFIG_RAAH_BENCH)
        target_compile_definitions(${COMPONENT_LIB} PUBLIC UI_MAP_STATS)
    endif()
endif()
