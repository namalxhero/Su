cmake_minimum_required(VERSION 3.22.1)
project("su_native")

add_library(su_native SHARED
    su_native.cpp)

find_library(log-lib log)

target_link_libraries(su_native ${log-lib})
