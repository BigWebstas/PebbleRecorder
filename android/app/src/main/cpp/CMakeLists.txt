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

# Reproducible builds. F-Droid recompiles this on its own build server and compares the result byte
# for byte with the APK we sign, so nothing here may depend on where the checkout sits or on what
# git says about it:
#  1. GGML_ABORT and WHISPER_ASSERT expand to __FILE__, so every source file's absolute path ends
#     up in .rodata (where stripping can't remove it). Map the source and build dirs to relative ones.
#  2. ggml runs `git rev-parse --short HEAD` at configure time and bakes it into the library as
#     GGML_COMMIT; an abbreviated hash is only as long as the local object count needs, and the
#     result differs between checkouts. Pointing GIT_EXE at a git that doesn't exist makes
#     ggml's find_program a no-op and its commit fall back to "unknown".
add_compile_options(
    "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=."
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=.build")
set(GIT_EXE "/nonexistent/git" CACHE FILEPATH "" FORCE)

add_subdirectory(whisper.cpp)

add_library(pebblewhisper SHARED whisper_jni.cpp)
target_link_libraries(pebblewhisper whisper log)
