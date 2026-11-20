cmake_minimum_required(VERSION 3.10)

project(external)

# Prevent the NDK toolchain from re-enabling host-side C++ module scanning in nested projects.
set(CMAKE_CXX_SCAN_FOR_MODULES OFF CACHE BOOL "" FORCE)

set(CMAKE_CXX_STANDARD 23)

set(XZ_SOURCES
	xz_crc32.c
	xz_crc64.c
	# xz_dec_bcj.c
	xz_dec_lzma2.c
	xz_dec_stream.c)
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

# CMake initializes CXX_MODULE_STD on each target at target creation time.
# Disable import-std before creating LSPlant targets; changing the target
# property after add_subdirectory() is too late for CMake's synthesized std
# module dependency. Keep CXX_SCAN_FOR_MODULES enabled for LSPlant's own
# CXX_MODULES file sets. Store OFF in the cache as well as the directory scope:
# nested project() calls/toolchain initialization must not restore host std modules.
if(CMAKE_VERSION VERSION_GREATER_EQUAL "3.30")
	set(CMAKE_CXX_MODULE_STD OFF)
	set(CMAKE_CXX_MODULE_STD OFF CACHE BOOL "" FORCE)
endif()
add_subdirectory(lsplant/lsplant/src/main/jni)

target_compile_options(lsplant_static PUBLIC -Wno-gnu-anonymous-struct
	# lsplant.cc's `operator""_uarr()` is a string literal operator template, which is a GNU
	# extension clang warns about under -Wpedantic. Upstream code we do not own, and the
	# warning was printed once per ABI on every build.
	-Wno-gnu-string-literal-operator-template)
target_compile_definitions(fmt-header-only INTERFACE FMT_USE_LOCALE=0 FMT_USE_FLOAT=0 FMT_USE_DOUBLE=0 FMT_USE_LONG_DOUBLE=0 FMT_USE_BITINT=0)
