/*
 * cppcore.h —— C/C++ 核心的公共接口（纯 C ABI）。
 *
 * 设计约束（模板的核心）：
 *  1. **只暴露 C 接口**（`extern "C"`）：Swift 能直接 import，JNI 也能直接调；
 *     不把 `std::string` / 模板 / 类暴露到边界上（Swift 用不了，ABI 也不稳定）。
 *  2. **不依赖任何平台头文件**：同一份源码同时被 Android 的 CMake 和 iOS 的 SPM 编译。
 *  3. **缓冲区约定**：谁分配谁释放；`cppcore_buffer_alloc` 分配的内存只能用
 *     `cppcore_buffer_free` 释放（内部是 malloc/free，不要混用 delete）。
 *
 * 想加自己的 C/C++ 代码时：改这里 + cpp_core.cpp / c_core.c，
 * 再在 Android 的 bridge.cpp（JNI）与 iOS 的 CppBridgePlugin.swift 各加一个转发即可。
 */
#ifndef CPPCORE_H
#define CPPCORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** 返回构建信息（静态 JSON 字符串，生命周期 = 进程）。 */
const char* cppcore_build_info(void);

/* ------------------------------------------------------------------ C++ 示例 */

/** 两数相加（由 cpp_core.cpp 的 C++ 编译单元实现）。 */
int32_t cppcore_add(int32_t a, int32_t b);

/* ------------------------------------------------------------------ C 示例 */

/** 两数相加（由 c_core.c 用 **C 编译器** 编译，验证纯 C 代码能进同一个库）。 */
int32_t cppcore_c_add(int32_t a, int32_t b);

/** 字符串长度（C 实现，验证 C 侧处理 char*）。 */
int32_t cppcore_c_strlen(const char* text);

/* ------------------------------------------------------------------ 内存块（零拷贝演示） */

/**
 * 分配一块 native 内存并返回指针（内部用 malloc）。
 *
 * ⚠️ 必须用 `cppcore_buffer_free` 释放，否则内存泄漏。返回 NULL 表示分配失败。
 */
void* cppcore_buffer_alloc(int64_t size);

/** 把 buffer 的每个 32 位整数填成 value；返回 0 成功，<0 参数错误。 */
int32_t cppcore_buffer_fill(void* ptr, int64_t size, int32_t value);

/**
 * 把 buffer 按 32 位整数求和（不拷贝、不分配）。
 *
 * @param out 结果写到这里（调用方分配）。
 * @return 0 成功；<0 参数错误。
 */
int32_t cppcore_buffer_sum(const void* ptr, int64_t size, int64_t* out);

/** 释放 `cppcore_buffer_alloc` 分配的内存。 */
void cppcore_buffer_free(void* ptr);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* CPPCORE_H */
