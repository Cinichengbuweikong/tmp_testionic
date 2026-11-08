# CMakeLists.txt —— Android 侧构建脚本。
#
# 目标：把 **共享核心**（shared/core：C++ + 纯 C 各一个编译单元）和 **JNI 薄桥**（bridge.cpp）
# 编成一个 .so（libcppbridge.so）。
#
# 关键点：
#  - SHARED_DIR 指向插件根目录的 shared/core（CMake 允许引用模块目录之外的源码）；
#    如果某些 Gradle/AGP 版本限制外部路径，把 shared/core 整个拷进 android/src/main/cpp/ 即可。
#  - CXX_STANDARD 设为 17：与 iOS 侧 `cxxLanguageStandard: .cxx17` 保持一致。
#  - 纯 C 文件（c_core.c）由 CMake 自动用 C 编译器处理（project(... LANGUAGES C CXX)）。

cmake_minimum_required(VERSION 3.22)

project(cppbridge LANGUAGES C CXX)

set(SHARED_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../shared/core")

add_library(cppbridge SHARED
    bridge.cpp
    ${SHARED_DIR}/cpp_core.cpp
    ${SHARED_DIR}/c_core.c
)

target_include_directories(cppbridge PRIVATE
    ${SHARED_DIR}/include
)

set_target_properties(cppbridge PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

# Android 日志（模板里没用到，需要打日志时取消注释并 #include <android/log.h>）
# target_link_libraries(cppbridge PRIVATE log)
