/*
 * c_core.c —— **纯 C 编译单元**（用 C 编译器编译，不是 C++）。
 *
 * 它的存在只是为了证明：这个模板不只能跑 C++，**纯 C 代码也能进同一个 native 库**。
 * 注意：C 文件里不能出现 C++ 语法（class/namespace/template），但可以包含 cppcore.h ——
 * 因为头文件里的 `extern "C"` 只在 __cplusplus 下生效，C 编译器会自动忽略。
 */
#include "cppcore.h"

#include <string.h>

int32_t cppcore_c_add(int32_t a, int32_t b) {
    return a + b;
}

int32_t cppcore_c_strlen(const char* text) {
    if (text == NULL) {
        return -1;
    }
    return (int32_t)strlen(text);
}
