cmake_minimum_required(VERSION 3.22.1)

project("jackpal-termexec2")

add_library(jackpal-termexec2 SHARED
        process.cpp)

find_library(log-lib log)

target_link_libraries(jackpal-termexec2
        ${log-lib}
        c)

target_link_options(jackpal-termexec2 PRIVATE "-Wl,-z,max-page-size=16384")
