/**
 * CapISHBridge 的实现：有 iSH-ARM64 就走真引擎，没有就编成桩。
 *
 * 真引擎路径只依赖 `ISHShellExecutor.h`（iSH-ARM64 的 ObjC 门面，提供
 * `+executeCommand:lineCallback:completion:` / `+killProcess:withSignal:`），
 * 不碰 iSH 内部的 C 代码，所以引擎升级不会影响这里。
 */
#import "CapISHBridge.h"

#if __has_include("ISHShellExecutor.h")
#import "ISHShellExecutor.h"
#define CAPISH_HAS_ENGINE 1
#else
#define CAPISH_HAS_ENGINE 0
#endif

#include <string.h>

static const char *kNoEngine =
    "iOS 引擎未链接：需要把 iSH-ARM64（everettjf/dsh-ios 的 ish-arm64）构建成 "
    "xcframework 并链接进宿主 App，然后用 plugins/capacitor-linux/scripts/"
    "fetch-ish-ios-runtime.sh 生成；详见 README「iOS 路线」";

int capish_available(void) {
#if CAPISH_HAS_ENGINE
    return NSClassFromString(@"ISHShellExecutor") != nil ? 1 : 0;
#else
    return 0;
#endif
}

const char *capish_unavailable_reason(void) {
    return kNoEngine;
}

int capish_exec(const char *utf8_command,
                capish_line_cb on_line,
                capish_done_cb on_done,
                void *user) {
    if (utf8_command == NULL) {
        if (on_done) {
            on_done(-1, 0, 0.0, user);
        }
        return -1;
    }

#if CAPISH_HAS_ENGINE
    Class executor = NSClassFromString(@"ISHShellExecutor");
    if (executor == Nil) {
        if (on_done) {
            on_done(-1, 0, 0.0, user);
        }
        return -1;
    }
    NSString *command = [NSString stringWithUTF8String:utf8_command];
    if (command == nil) {
        if (on_done) {
            on_done(-1, 0, 0.0, user);
        }
        return -1;
    }

    ISHShellLineCallback line_cb = nil;
    if (on_line) {
        line_cb = ^(NSString *line, BOOL isStdErr) {
            const char *utf8 = line.UTF8String;
            if (utf8 != NULL) {
                on_line(utf8, isStdErr ? 1 : 0, user);
            }
        };
    }
    ISHShellCompletionCallback done_cb = nil;
    if (on_done) {
        done_cb = ^(ISHShellExecutionResult *result) {
            int code = result != nil ? result.exitCode : -1;
            int pid = result != nil ? result.pid : 0;
            double duration = result != nil ? result.duration : 0.0;
            on_done(code, pid, duration, user);
        };
    }

    int pid = [executor executeCommand:command
                          lineCallback:line_cb
                            completion:done_cb];
    if (pid < 0 && on_done) {
        // 引擎拒绝创建进程：保证 done 回调一定发生。
        on_done(pid, 0, 0.0, user);
    }
    return pid;
#else
    // 桩：立即失败，调用方据此给出明确错误。
    if (on_done) {
        on_done(-1, 0, 0.0, user);
    }
    return -1;
#endif
}

int capish_kill(int pid, int signal_number) {
    if (pid <= 0) {
        return 0;
    }
#if CAPISH_HAS_ENGINE
    Class executor = NSClassFromString(@"ISHShellExecutor");
    if (executor == Nil) {
        return 0;
    }
    return [executor killProcess:pid withSignal:signal_number] ? 1 : 0;
#else
    (void) signal_number;
    return 0;
#endif
}
