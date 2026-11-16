cmake_minimum_required(VERSION 3.22.1)

project(local_llm_reference_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(LLAMA_CPP_SOURCE "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp")
if(NOT EXISTS "${LLAMA_CPP_SOURCE}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp submodule is missing. Run: git submodule update --init --recursive")
endif()

# Controlled CPU reference configuration. Optional backends are separate experiments.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

if(ANDROID)
    # The Android NDK provides the target Vulkan loader library. Vulkan C++ headers and
    # shader tools are host build dependencies and must be resolved outside the NDK sysroot.
    find_path(_host_vulkan_include_dir
        NAMES vulkan/vulkan.hpp
        HINTS "$ENV{VULKAN_SDK}/Include" "$ENV{VULKAN_SDK}/include"
        PATHS /usr/include /usr/local/include
        NO_CMAKE_FIND_ROOT_PATH
        REQUIRED
    )
    if(NOT EXISTS "${_host_vulkan_include_dir}/vulkan/vulkan.hpp")
        message(FATAL_ERROR "Host Vulkan C++ header not found: ${_host_vulkan_include_dir}/vulkan/vulkan.hpp")
    endif()
    set(Vulkan_INCLUDE_DIR "${_host_vulkan_include_dir}" CACHE PATH "" FORCE)
    # NDK clang replaces host default include paths with the Android sysroot. Pass the
    # host Vulkan-Hpp path explicitly; CMake may otherwise suppress /usr/include as implicit.
    add_compile_options("$<$<COMPILE_LANGUAGE:CXX>:-I${_host_vulkan_include_dir}>")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(_vulkan_android_triple "aarch64-linux-android")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(_vulkan_android_triple "x86_64-linux-android")
    else()
        message(FATAL_ERROR "Unsupported Vulkan Android ABI: ${ANDROID_ABI}")
    endif()
    set(Vulkan_LIBRARY "${CMAKE_SYSROOT}/usr/lib/${_vulkan_android_triple}/24/libvulkan.so" CACHE FILEPATH "" FORCE)
    find_program(Vulkan_GLSLC_EXECUTABLE NAMES glslc REQUIRED)
endif()
find_package(Vulkan COMPONENTS glslc REQUIRED)
# SPIRV-Headers is a host-side header package used while compiling Vulkan shaders.
# Do not search the Android NDK sysroot for this host package during cross-compilation.
find_package(SPIRV-Headers CONFIG REQUIRED NO_CMAKE_FIND_ROOT_PATH)

add_subdirectory("${LLAMA_CPP_SOURCE}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

add_library(llm_reference SHARED jni_bridge.cpp)
target_include_directories(llm_reference PRIVATE
    "${LLAMA_CPP_SOURCE}/include"
    "${LLAMA_CPP_SOURCE}/ggml/include"
)
target_compile_options(llm_reference PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(llm_reference PRIVATE llama android log)
