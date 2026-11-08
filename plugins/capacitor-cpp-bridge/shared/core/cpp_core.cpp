/*
 * cpp_core.cpp —— C++ 编译单元。
 *
 * 这里可以自由使用 STL、模板、类、异常 —— 只要不把它们暴露到 cppcore.h 的 C 接口上。
 * 这份文件同时被 Android（CMake）和 iOS（SPM）编译。
 */
#include "cppcore.h"

#include <cstdio>
#include <cstdlib>
#include <string>

namespace {

const std::string& buildInfoCache() {
    static const std::string info = [] {
        std::string json = "{\"platform\":\"";
#if defined(__ANDROID__)
        json += "android";
#elif defined(__APPLE__)
        json += "ios";
#else
        json += "unknown";
#endif
        json += "\",\"compiler\":\"";
#if defined(__clang__)
        char buffer[64];
        std::snprintf(buffer, sizeof(buffer), "clang %d.%d.%d", __clang_major__, __clang_minor__, __clang_patchlevel__);
        json += buffer;
#elif defined(__GNUC__)
        char buffer[64];
        std::snprintf(buffer, sizeof(buffer), "gcc %d.%d.%d", __GNUC__, __GNUC_MINOR__, __GNUC_PATCHLEVEL__);
        json += buffer;
#else
        json += "unknown";
#endif
        json += "\",\"arch\":\"";
#if defined(__aarch64__)
        json += "aarch64";
#elif defined(__arm__)
        json += "armv7";
#elif defined(__x86_64__)
        json += "x86_64";
#else
        json += "unknown";
#endif
        json += "\",\"cxx\":\"";
#if __cplusplus >= 202002L
        json += "C++20";
#elif __cplusplus >= 201703L
        json += "C++17";
#elif __cplusplus >= 201402L
        json += "C++14";
#else
        json += "C++11";
#endif
        json += "\"}";
        return json;
    }();
    return info;
}

} // namespace

extern "C" {

const char* cppcore_build_info(void) {
    return buildInfoCache().c_str();
}

int32_t cppcore_add(int32_t a, int32_t b) {
    return a + b;
}

void* cppcore_buffer_alloc(int64_t size) {
    if (size <= 0) {
        return nullptr;
    }
    return std::malloc(static_cast<size_t>(size));
}

int32_t cppcore_buffer_fill(void* ptr, int64_t size, int32_t value) {
    if (ptr == nullptr || size <= 0 || (size % 4) != 0) {
        return -1;
    }
    int32_t* words = static_cast<int32_t*>(ptr);
    const int64_t count = size / 4;
    for (int64_t i = 0; i < count; ++i) {
        words[i] = value;
    }
    return 0;
}

int32_t cppcore_buffer_sum(const void* ptr, int64_t size, int64_t* out) {
    if (ptr == nullptr || size <= 0 || (size % 4) != 0 || out == nullptr) {
        return -1;
    }
    const int32_t* words = static_cast<const int32_t*>(ptr);
    const int64_t count = size / 4;
    int64_t sum = 0;
    for (int64_t i = 0; i < count; ++i) {
        sum += words[i];
    }
    *out = sum;
    return 0;
}

void cppcore_buffer_free(void* ptr) {
    if (ptr != nullptr) {
        std::free(ptr);
    }
}

} // extern "C"
