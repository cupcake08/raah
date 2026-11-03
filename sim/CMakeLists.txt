# Host renderer: runs the real UI code against scripted ride scenarios and writes PNG screenshots.
cmake_minimum_required(VERSION 3.16)
project(raah_sim C)

set(LVGL_DIR "${CMAKE_CURRENT_LIST_DIR}/../firmware/managed_components/lvgl__lvgl" CACHE PATH "LVGL source (fetched by the firmware build)")
if(NOT EXISTS "${LVGL_DIR}/lvgl.h")
    message(FATAL_ERROR "LVGL not found at ${LVGL_DIR}. Run `idf.py reconfigure` in firmware/ first, or pass -DLVGL_DIR=...")
endif()

set(LV_CONF_PATH "${CMAKE_CURRENT_LIST_DIR}/lv_conf.h" CACHE STRING "" FORCE)
set(LV_CONF_BUILD_DISABLE_EXAMPLES ON CACHE BOOL "" FORCE)
set(LV_CONF_BUILD_DISABLE_DEMOS ON CACHE BOOL "" FORCE)
set(LV_CONF_BUILD_DISABLE_THORVG_INTERNAL ON CACHE BOOL "" FORCE)
add_subdirectory("${LVGL_DIR}" lvgl EXCLUDE_FROM_ALL)

file(GLOB UI_SRCS CONFIGURE_DEPENDS ../ui/*.c ../ui/fonts/*.c)
add_executable(raah_sim main.c ../bench/ui_bench.c ${UI_SRCS})
target_include_directories(raah_sim PRIVATE ../ui ../bench ${CMAKE_CURRENT_LIST_DIR})
target_compile_definitions(raah_sim PRIVATE LV_LVGL_H_INCLUDE_SIMPLE LV_CONF_INCLUDE_SIMPLE UI_MAP_STATS)
target_compile_options(raah_sim PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(raah_sim PRIVATE lvgl m)
