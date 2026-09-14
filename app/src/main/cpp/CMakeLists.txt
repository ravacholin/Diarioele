cmake_minimum_required(VERSION 3.22.1)
project(diarioclase_whisper LANGUAGES C CXX)

include(FetchContent)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG 927cfce34f31707e17f2bff35c349632fb9e2c3a
    GIT_SHALLOW FALSE
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(whispercpp)

add_library(diarioclase_whisper SHARED WhisperJni.cpp)
target_compile_features(diarioclase_whisper PRIVATE cxx_std_17)
target_compile_options(diarioclase_whisper PRIVATE -O3)
target_link_libraries(diarioclase_whisper PRIVATE whisper android log)
