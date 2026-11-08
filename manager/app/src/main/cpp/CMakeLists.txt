cmake_minimum_required(VERSION 3.22.1)
project("su_native")

add_library(su_native SHARED
    su_native.cpp)

find_library(log-lib log)

target_link_libraries(su_native ${log-lib})

# kernel/include/su_uapi.h is the single source of truth for the
# kernel<->userspace ABI (see that file's own comment). Point the
# native build straight at it instead of hand-duplicating the struct
# and ioctl definitions here, so the two can never silently drift.
target_include_directories(su_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../kernel/include)
