cmake_minimum_required(VERSION 3.22.1)

project(exo_dovi_renderer LANGUAGES CXX)

add_library(exo_dovi_renderer SHARED exo_dovi_renderer.cpp)

set(EXO_DV5_NATIVE_ROOT
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/exo-dv5-native")
set(EXO_DV5_NATIVE_LIB_DIR
        "${EXO_DV5_NATIVE_ROOT}/prebuilt/${ANDROID_ABI}")

add_library(exo_dv5_placebo STATIC IMPORTED)
set_target_properties(exo_dv5_placebo PROPERTIES
        IMPORTED_LOCATION "${EXO_DV5_NATIVE_LIB_DIR}/libplacebo.a")

add_library(exo_dv5_shaderc STATIC IMPORTED)
set_target_properties(exo_dv5_shaderc PROPERTIES
        IMPORTED_LOCATION "${EXO_DV5_NATIVE_LIB_DIR}/libshaderc.a")

add_library(exo_dv5_dovi STATIC IMPORTED)
set_target_properties(exo_dv5_dovi PROPERTIES
        IMPORTED_LOCATION "${EXO_DV5_NATIVE_LIB_DIR}/libdovi.a")

target_compile_features(exo_dovi_renderer PRIVATE cxx_std_20)
target_compile_options(exo_dovi_renderer PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden)
target_compile_definitions(exo_dovi_renderer PRIVATE
        PL_STATIC
        PL_HAVE_PTHREAD
        PTHREAD_HAS_SETCLOCK)
target_include_directories(exo_dovi_renderer PRIVATE
        "${EXO_DV5_NATIVE_ROOT}/include")
target_link_options(exo_dovi_renderer PRIVATE -Wl,--no-undefined)

find_library(android-lib android REQUIRED)
find_library(log-lib log REQUIRED)
find_library(media-ndk-lib mediandk REQUIRED)
find_library(vulkan-lib vulkan REQUIRED)
find_library(dl-lib dl REQUIRED)
find_library(m-lib m REQUIRED)

target_link_libraries(exo_dovi_renderer PRIVATE
        exo_dv5_placebo
        exo_dv5_shaderc
        exo_dv5_dovi
        ${android-lib}
        ${log-lib}
        ${media-ndk-lib}
        ${vulkan-lib}
        ${dl-lib}
        ${m-lib})

# ---- Trickplay frame extractor -------------------------------------------------
# Reuses the FFmpeg 7.1.4 build that already ships with the app for mpv
# (isolated names libmvformat/libmvcodec/libmvutil/libmwscale, opened at runtime
# with dlopen so nothing is baked into the linker namespace or DT_NEEDED).
set(FFMPEG_HEADER_DIR
        "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/ffmpeg-7.1.4/include")

add_library(trickplay_extractor SHARED trickplay_extractor.cpp)
target_compile_features(trickplay_extractor PRIVATE cxx_std_17)
target_include_directories(trickplay_extractor PRIVATE "${FFMPEG_HEADER_DIR}")
target_compile_options(trickplay_extractor PRIVATE -O2)
target_link_options(trickplay_extractor PRIVATE -Wl,--no-undefined)

target_link_libraries(trickplay_extractor PRIVATE ${log-lib} ${m-lib} ${dl-lib} ${android-lib})
