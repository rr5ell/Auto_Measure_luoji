cmake_minimum_required(VERSION 3.18)
project(yuv_preprocess)

add_library(yuv_preprocess SHARED yuv_preprocess.cpp)

target_link_libraries(yuv_preprocess
    log
    android
)

# 16KB 页面对齐（Android 15+ / Android 16 要求）
target_link_options(yuv_preprocess PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

add_library(sherpa_onnx_c_api SHARED IMPORTED)
set_target_properties(sherpa_onnx_c_api PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsherpa-onnx-c-api.so"
)
add_library(sherpa_onnx_cxx_api SHARED IMPORTED)
set_target_properties(sherpa_onnx_cxx_api PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsherpa-onnx-cxx-api.so"
)
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libonnxruntime.so"
)

add_library(sherpa_hotword_jni SHARED sherpa_hotword_jni.cpp)
target_include_directories(sherpa_hotword_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
)
target_link_libraries(sherpa_hotword_jni
    sherpa_onnx_c_api
    sherpa_onnx_cxx_api
    onnxruntime
    log
    android
)
target_link_options(sherpa_hotword_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
