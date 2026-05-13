cmake_minimum_required(VERSION 3.22.1)
project("kala_whisper_jni")

# Disable everything we don't need for Android
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Use whisper.cpp's own build system
add_subdirectory(whisper)

# Your JNI bridge
add_library(kala_whisper_jni SHARED whisper_jni.cpp)

target_include_directories(kala_whisper_jni PRIVATE
    whisper/include
    whisper/ggml/include
)

target_link_libraries(kala_whisper_jni
    whisper
    android
    log
)