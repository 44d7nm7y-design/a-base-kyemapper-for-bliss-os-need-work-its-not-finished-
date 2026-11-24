cmake_minimum_required(VERSION 3.22.1)

project("gaming_keyboard_native" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# The JSON library is a single header file that is not part of Android.
# Download json.hpp (version 3.11.x) from the nlohmann/json releases page
# on GitHub and save it exactly here:
#   cpp_service/third_party/nlohmann/json.hpp
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/third_party/nlohmann/json.hpp")
    message(FATAL_ERROR
        "Missing third_party/nlohmann/json.hpp. "
        "Download json.hpp from the nlohmann/json releases and save it at "
        "cpp_service/third_party/nlohmann/json.hpp")
endif()

add_library(
        gaming_keyboard_native
        SHARED
        keymap_store.cpp
        settings_provider.cpp
        game_detector.cpp
        keymap_jni.cpp
        detector_jni.cpp
)

target_include_directories(
        gaming_keyboard_native
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)

find_library(log-lib log)

target_link_libraries(
        gaming_keyboard_native
        ${log-lib}
)

# Newer Android versions require this memory alignment for native libraries.
target_link_options(gaming_keyboard_native PRIVATE -Wl,-z,max-page-size=16384)
