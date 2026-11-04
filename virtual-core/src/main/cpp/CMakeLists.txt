cmake_minimum_required(VERSION 3.22.1)
project("aegis_virtual_engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 16 KB page size alignment mandatory for Android 13+ / NDK 27 compliance
set(AEGIS_PAGE_ALIGN_FLAGS "-Wl,-z,max-page-size=16384")

add_library(
    aegis_guest
    SHARED
    aegis_guest.cpp
)

find_library(
    log-lib
    log
)

target_link_options(
    aegis_guest
    PRIVATE
    ${AEGIS_PAGE_ALIGN_FLAGS}
)

target_link_libraries(
    aegis_guest
    ${log-lib}
)
