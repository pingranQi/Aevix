cmake_minimum_required(VERSION 3.10)

project(external)

# Prevent the NDK toolchain from re-enabling host-side C++ module scanning in nested projects.
set(CMAKE_CXX_SCAN_FOR_MODULES OFF CACHE BOOL "" FORCE)

set(CMAKE_CXX_STANDARD 23)

set(XZ_SOURCES
\txz_crc32.c
\txz_crc64.c
\t# xz_dec_bcj.c
\txz_dec_lzma2.c
\txz_dec_stream.c)
set(XZ_INCLUDES xz-embedded/linux/include/linux xz-embedded/userspace)
list(TRANSFORM XZ_SOURCES PREPEND xz-embedded/linux/lib/xz/)
add_library(xz_static STATIC ${XZ_SOURCES})
target_compile_options(xz_static PRIVATE -DXZ_USE_CRC64)
target_include_directories(xz_static PRIVATE ${XZ_INCLUDES})

option(LSPLANT_BUILD_SHARED OFF)
option(Plugin.SymbolResolver OFF)
option(FMT_INSTALL OFF)

add_subdirectory(dobby)
add_subdirectory(fmt)
add_subdirectory(lsplant/lsplant/src/main/jni)

# LSPlant needs module scanning for its own CXX_MODULES file sets, but it does not
# import the C++ standard library module. On newer CMake/toolchain combinations,
# leaving CXX_MODULE_STD enabled makes CMake synthesize a host std.cc module and
# clang-scan-deps tries to parse the host libstdc++ headers with the Android NDK
# compiler (failing on bits/stdc++.h). Disable only import-std support; keep
# CXX_SCAN_FOR_MODULES enabled on LSPlant's module targets.
if(CMAKE_VERSION VERSION_GREATER_EQUAL "3.30")
\tforeach(target IN ITEMS lsplant_static lsplant)
\t\tif(TARGET ${target})
\t\t\tset_property(TARGET ${target} PROPERTY CXX_MODULE_STD OFF)
\t\tendif()
\tendforeach()
endif()

target_compile_options(lsplant_static PUBLIC -Wno-gnu-anonymous-struct
\t# lsplant.cc's `operator""_uarr()` is a string literal operator template, which is a GNU
\t# extension clang warns about under -Wpedantic. Upstream code we do not own, and the
\t# warning was printed once per ABI on every build.
\t-Wno-gnu-string-literal-operator-template)
target_compile_definitions(fmt-header-only INTERFACE FMT_USE_LOCALE=0 FMT_USE_FLOAT=0 FMT_USE_DOUBLE=0 FMT_USE_LONG_DOUBLE=0 FMT_USE_BITINT=0)
