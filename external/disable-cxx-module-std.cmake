# Included by CMake immediately after a nested project() command returns.
# This runs after the toolchain has initialized language settings but before
# the subproject creates targets, so CXX_MODULE_STD is OFF when target
# properties are initialized. Do not disable CXX_SCAN_FOR_MODULES: LSPlant
# relies on scanning its own CXX_MODULES file sets.
if(CMAKE_VERSION VERSION_GREATER_EQUAL "3.30")
  set(CMAKE_CXX_MODULE_STD OFF)
  set(CMAKE_CXX_MODULE_STD OFF CACHE BOOL "" FORCE)
endif()
