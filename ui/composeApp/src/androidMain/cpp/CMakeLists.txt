cmake_minimum_required(VERSION 3.22.1)
project(framemark_engine CXX)

# JNI surface. The product ships the complete native renderer on arm64-v8a only.
add_library(framemark_engine SHARED fm_engine_jni.cpp)
target_compile_features(framemark_engine PRIVATE cxx_std_17)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Unsupported FrameMark Android ABI '${ANDROID_ABI}'; only arm64-v8a has a complete renderer")
endif()

if(NOT FM_PREBUILT_ROOT)
    set(FM_PREBUILT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/arm64-v8a")
endif()

set(FM_ENGINE_A "${FM_PREBUILT_ROOT}/libfm_android_engine.a")
set(FM_HAND_A "${FM_PREBUILT_ROOT}/libfm_android_hand.a")
set(FM_EXPORT_A "${FM_PREBUILT_ROOT}/libfm_android_export.a")

foreach(FM_REQUIRED_ARCHIVE IN ITEMS "${FM_ENGINE_A}" "${FM_HAND_A}" "${FM_EXPORT_A}")
    if(NOT EXISTS "${FM_REQUIRED_ARCHIVE}")
        message(FATAL_ERROR "Required FrameMark arm64 renderer archive is missing: ${FM_REQUIRED_ARCHIVE}")
    endif()
endforeach()

add_library(fm_android_engine STATIC IMPORTED)
set_target_properties(fm_android_engine PROPERTIES IMPORTED_LOCATION "${FM_ENGINE_A}")
target_compile_definitions(framemark_engine PRIVATE FM_ENGINE_LINKED=1)
target_link_libraries(framemark_engine fm_android_engine)

add_library(fm_android_hand STATIC IMPORTED)
set_target_properties(fm_android_hand PROPERTIES IMPORTED_LOCATION "${FM_HAND_A}")
target_compile_definitions(framemark_engine PRIVATE FM_HAND_LINKED=1)
target_link_libraries(framemark_engine fm_android_hand)

add_library(fm_android_export STATIC IMPORTED)
set_target_properties(fm_android_export PROPERTIES IMPORTED_LOCATION "${FM_EXPORT_A}")
target_compile_definitions(framemark_engine PRIVATE FM_EXPORT_LINKED=1)
target_link_libraries(framemark_engine fm_android_export)

target_link_libraries(framemark_engine android log EGL GLESv2 dl m z)
