cmake_minimum_required(VERSION 3.22.1)
project("su_native")

add_library(su_native SHARED
    su_native.cpp)

# generated/su_uapi.h is copied here by the :app:syncUapiHeader Gradle task
# from kernel/include/su_uapi.h - the single source of truth for the ABI.
target_include_directories(su_native PRIVATE generated)

find_library(log-lib log)

target_link_libraries(su_native ${log-lib})
