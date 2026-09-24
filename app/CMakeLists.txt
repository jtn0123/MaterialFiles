cmake_minimum_required(VERSION 3.6)

project(MaterialFiles C)

# C23 for [[maybe_unused]] on the JNI parameters every native method has to declare.
set(C_DIALECT -std=gnu23)

add_library(hiddenapi SHARED
        src/main/jni/hiddenapi.c)
target_compile_options(hiddenapi
        PRIVATE
        ${C_DIALECT}
        -Wall
        -Werror)

find_library(LOG_LIBRARY log)
add_library(syscall SHARED
        src/main/jni/syscall.c)
target_compile_options(syscall
        PRIVATE
        ${C_DIALECT}
        -Wall
        -Werror)
target_link_libraries(syscall
        PRIVATE
        ${LOG_LIBRARY})
