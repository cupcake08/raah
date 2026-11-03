cmake_minimum_required(VERSION 3.16)

# Portable UI code lives outside the firmware so the host simulator can share it.
set(EXTRA_COMPONENT_DIRS "${CMAKE_CURRENT_LIST_DIR}/../ui")

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(raah)
