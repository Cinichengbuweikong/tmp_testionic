/*
 * PythonShim —— 给 Swift 用的 C 包装层。
 *
 * 为什么需要它：
 *   Python-Apple-support 提供的是**静态** libPython.a，SPM 的 binaryTarget 不会给 Swift
 *   生成模块（没有 module map），Swift 无法直接 `import Python`。所以这里用一个 C target
 *   把需要的 Py_* 调用包成稳定的小 API，Swift 只依赖这个 target。
 *
 * 生命周期：cp_init 只能调用一次；cp_shutdown 在进程退出/测试结束时调用。
 */
#ifndef PYTHON_SHIM_H
#define PYTHON_SHIM_H

#ifdef __cplusplus
extern "C" {
#endif

/** 输出回调：session / stream("stdout"|"stderr") / text */
typedef void (*cp_emit_fn)(const char *session, const char *stream, const char *text);

/** 输入回调：返回一行（带换行符，C 字符串由 shim 释放）；返回 NULL 表示 EOF */
typedef char *(*cp_readline_fn)(const char *session);

/** 设置回调（必须在 cp_init 之前调用）。 */
void cp_set_output(cp_emit_fn emit);
void cp_set_input(cp_readline_fn read_line);

/**
 * 初始化解释器。
 *
 * @param home       PYTHONHOME（包含 lib/python3.x 的目录）
 * @param stdlib     stdlib 目录（PYTHONPATH 的第一项）
 * @param runtime_src `capython/runtime.py` 的源码（会被执行成模块 capython_runtime）
 * @return 0 成功；非 0 失败（失败信息通过 cp_last_error 取）
 */
int cp_init(const char *home, const char *stdlib, const char *runtime_src);

/** 执行代码，返回 JSON（malloc 分配，调用方 free）。 */
char *cp_run(const char *session, const char *source);

/** 解释器版本（静态字符串，如 "3.11.18"）。 */
const char *cp_version(void);

/** 最近一次初始化失败的原因（静态字符串）。 */
const char *cp_last_error(void);

/** 释放解释器。 */
void cp_shutdown(void);

#ifdef __cplusplus
}
#endif

#endif /* PYTHON_SHIM_H */
