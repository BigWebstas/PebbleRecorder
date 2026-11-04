cmake_minimum_required(VERSION 3.22.1)
project(pebblewhisper LANGUAGES C CXX)

# Only the library is needed - none of whisper.cpp's example programs or tests.
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# Cross-compiling: don't tune for the build machine's CPU, and skip OpenMP (not shipped with the NDK's static libc++).
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp)

add_library(pebblewhisper SHARED whisper_jni.cpp)
target_link_libraries(pebblewhisper whisper log)
