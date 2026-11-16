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
    find_path(_host_spirv_include_dir
        NAMES spirv/unified1/spirv.hpp
        HINTS "$ENV{VULKAN_SDK}/Include" "$ENV{VULKAN_SDK}/include"
        PATHS /usr/include /usr/local/include
        NO_CMAKE_FIND_ROOT_PATH
        REQUIRED
    )
    set(Vulkan_INCLUDE_DIR "${_host_vulkan_include_dir}" CACHE PATH "" FORCE)

    # Never add host /usr/include to an Android compile: that would leak glibc headers
    # into NDK clang. Stage only the Vulkan, vk_video and SPIR-V header trees instead.
    set(_host_graphics_headers "${CMAKE_CURRENT_BINARY_DIR}/host-graphics-headers")
    file(MAKE_DIRECTORY "${_host_graphics_headers}")
    foreach(_header_tree vulkan vk_video)
        if(EXISTS "${_host_vulkan_include_dir}/${_header_tree}")
            file(COPY "${_host_vulkan_include_dir}/${_header_tree}" DESTINATION "${_host_graphics_headers}")
        endif()
    endforeach()
    file(COPY "${_host_spirv_include_dir}/spirv" DESTINATION "${_host_graphics_headers}")
    if(NOT EXISTS "${_host_graphics_headers}/vulkan/vulkan.hpp")
        message(FATAL_ERROR "Staged Vulkan C++ header is missing.")
    endif()
    if(NOT EXISTS "${_host_graphics_headers}/spirv/unified1/spirv.hpp")
        message(FATAL_ERROR "Staged SPIR-V header is missing.")
    endif()
    add_compile_options("$<$<COMPILE_LANGUAGE:CXX>:-I${_host_graphics_headers}>")
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

if(ANDROID AND TARGET ggml-vulkan)
    # Android API 24's Vulkan loader does not export the Vulkan 1.1
    # vkGetPhysicalDeviceFeatures2 symbol. Upstream already initializes a Vulkan-Hpp
    # dynamic dispatcher from vkGetInstanceProcAddr; route the three direct C calls
    # through that dispatcher so the native library has no API-28+ loader import.
    set(_upstream_vulkan_source "${LLAMA_CPP_SOURCE}/ggml/src/ggml-vulkan/ggml-vulkan.cpp")
    file(READ "${_upstream_vulkan_source}" _upstream_vulkan_contents)
    string(REGEX MATCHALL "vkGetPhysicalDeviceFeatures2\\(" _direct_features2_calls "${_upstream_vulkan_contents}")
    list(LENGTH _direct_features2_calls _direct_features2_count)
    if(NOT _direct_features2_count EQUAL 3)
        message(FATAL_ERROR
            "Pinned ggml Vulkan source changed: expected 3 direct vkGetPhysicalDeviceFeatures2 calls, found ${_direct_features2_count}. Review the Android API compatibility shim.")
    endif()
    string(REPLACE
        "vkGetPhysicalDeviceFeatures2("
        "ggml_vk_default_dispatcher().vkGetPhysicalDeviceFeatures2("
        _android_vulkan_contents
        "${_upstream_vulkan_contents}"
    )
    set(_android_vulkan_source "${CMAKE_CURRENT_BINARY_DIR}/ggml-vulkan-android.cpp")
    file(WRITE "${_android_vulkan_source}" "${_android_vulkan_contents}")

    # Replace (do not supplement) the upstream translation unit. Source-file properties
    # set from this parent directory do not reliably override a source added in the
    # nested ggml directory, so filter the target's actual SOURCES property explicitly.
    get_target_property(_ggml_vk_sources ggml-vulkan SOURCES)
    set(_ggml_vk_android_sources "")
    foreach(_source IN LISTS _ggml_vk_sources)
        get_filename_component(_source_name "${_source}" NAME)
        if(NOT _source_name STREQUAL "ggml-vulkan.cpp")
            if(IS_ABSOLUTE "${_source}")
                list(APPEND _ggml_vk_android_sources "${_source}")
            else()
                get_filename_component(_absolute_source "${_source}"
                    ABSOLUTE BASE_DIR "${LLAMA_CPP_SOURCE}/ggml/src/ggml-vulkan")
                list(APPEND _ggml_vk_android_sources "${_absolute_source}")
            endif()
        endif()
    endforeach()
    list(APPEND _ggml_vk_android_sources "${_android_vulkan_source}")
    set_property(TARGET ggml-vulkan PROPERTY SOURCES "${_ggml_vk_android_sources}")
    target_include_directories(ggml-vulkan PRIVATE "${LLAMA_CPP_SOURCE}/ggml/src/ggml-vulkan")
endif()

add_library(llm_reference SHARED jni_bridge.cpp)
target_include_directories(llm_reference PRIVATE
    "${LLAMA_CPP_SOURCE}/include"
    "${LLAMA_CPP_SOURCE}/ggml/include"
)
target_compile_options(llm_reference PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(llm_reference PRIVATE llama android log)
