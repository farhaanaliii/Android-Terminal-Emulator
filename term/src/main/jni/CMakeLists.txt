cmake_minimum_required(VERSION 3.22.1)

project("jackpal-androidterm5")

add_library(jackpal-androidterm5 SHARED
        common.cpp
        termExec.cpp
        fileCompat.cpp)

find_library(log-lib log)

target_link_libraries(jackpal-androidterm5
        ${log-lib})

target_link_options(jackpal-androidterm5 PRIVATE "-Wl,-z,max-page-size=16384")
