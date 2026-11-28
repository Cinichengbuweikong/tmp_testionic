/**
 * CapISHBridge — 把 iOS 上的 Linux 引擎（iSH-ARM64）包成一个小 C 接口。
 *
 * 为什么是 C 接口 + `__has_include` 开关（而不是直接在 Swift 里 import）：
 * - iSH-ARM64 不在本仓库里（它是几十万行 C + ObjC，需要 macOS 构建，见
 *   `scripts/fetch-ish-ios-runtime.sh`），所以**引擎可能不存在**；
 * - 如果 Swift 侧直接 `import`/调用 `ISHShellExecutor`，没有引擎时整个插件都编译不过，
 *   本仓库的 iOS CI（只做编译 + autotest）会一直红；
 * - 这里用 `__has_include("ISHShellExecutor.h")` 两路编译：有头文件就走真引擎，
 *   没有就编成一个"引擎未链接"的桩，插件在 iOS 上仍然能编译、能明确报错。
 *
 * 约定：所有函数都只在 iOS 上被调用；Linux/Capacitor 的其它平台不参与编译。
 */
#ifndef CAPISH_BRIDGE_H
#define CAPISH_BRIDGE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/** 逐行输出回调。is_stderr 非 0 表示来自 stderr。 */
typedef void (*capish_line_cb)(const char *utf8_line, int is_stderr, void *user);

/** 执行结束回调。 */
typedef void (*capish_done_cb)(int exit_code, int pid, double duration_seconds, void *user);

/**
 * 引擎是否已链接（= 宿主 App 里有没有 iSH-ARM64）。
 * 0 表示没有：`capish_exec` 会立即回调失败。
 */
int capish_available(void);

/**
 * 在客户机里执行一条 shell 命令（`/bin/sh -c`）。
 *
 * 回调都在主线程被调用；`capish_done_cb` 一定会被调用一次（包括失败）。
 * 返回值：客户机 pid（>0），或负的错误码（-1 = 引擎缺失/创建失败）。
 * 想取消就用 `capish_kill(pid)`。
 */
int capish_exec(const char *utf8_command,
                capish_line_cb on_line,
                capish_done_cb on_done,
                void *user);

/** 给客户机进程发信号（默认 SIGKILL 由调用方传 9）。返回 1 表示找到了进程。 */
int capish_kill(int pid, int signal_number);

/**
 * 启动客户机内核（iSH 的 boot 序列，实现在 scripts/vm/capish-boot-ios.c，编进
 * libcapish.a）。`rootfs_root` 是 fakefs 根目录（客户机的 / 映射到它下面的 data/）。
 *
 * 异步：立即返回；结果用 `capish_boot_state()` 轮询（2 = ready）。
 * 注意：**不 boot 就不能 `capish_exec`**（那样会永久阻塞）。
 */
int capish_boot(const char *rootfs_root);

/** 0=idle 1=booting 2=ready 3=failed（配合 `capish_boot`）。 */
int capish_boot_state(void);

/** boot 失败原因（静态缓冲，不要释放）。 */
const char *capish_boot_error(void);

/** 引擎缺失时给调用方看的说明（静态字符串，不要释放）。 */
const char *capish_unavailable_reason(void);

#ifdef __cplusplus
}
#endif

#endif /* CAPISH_BRIDGE_H */
