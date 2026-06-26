cmake_minimum_required(VERSION 3.22)

project("datespoof_hook")

add_library(datespoof_hook SHARED native_hook.c)

target_compile_options(datespoof_hook PRIVATE -O2 -Wall -fvisibility=hidden)

target_link_libraries(datespoof_hook dl log)
