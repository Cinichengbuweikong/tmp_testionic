/*
 * capish-boot-ios.c —— 在 iSH-ARM64 里**启动客户机内核**，然后让插件的 exec 通道可用。
 *
 * 为什么需要这个文件：`ISHShellExecutor`（我们 exec 用的门面）只做
 * `become_new_init_child()` → `do_execve()` → `task_start()`，它假设"客户机已经 boot 过"
 * （`current` 这个 init 任务存在）。没有 boot 时 `become_new_init_child()` 会**永久阻塞**，
 * 把调用线程冻住。iSH 的 boot 逻辑在 `xX_main_Xx.h`（`static inline`，只被 CLI 的 main.c 用），
 * 而且结尾会 `exit()` —— App 里不能这么做，所以这里把那段照搬过来、去掉 exit()。
 *
 * 这个文件**不编进 SPM target**（它需要 iSH 的内部头文件），而是由
 * scripts/fetch-ish-ios-runtime.sh 用 `-I <ish-arm64>` 编成 .o 塞进 libcapish.a；
 * 插件侧只看到下面 3 个函数（声明在 ios/Sources/CapISHBridge/CapISHBridge.h）。
 *
 * 调用时序（和 main.c 一致）：
 *   mount_root(&fakefs, "<root>/data")   // fakefs 的 / 映射到 <root>/data
 *   become_first_process(); current->thread = pthread_self();
 *   mknod /dev/{null,zero,full,random,urandom,tty,console,ptmx}
 *   do_execve("/bin/sh", ...)            // 常驻 init shell，后续 exec 的父进程
 *   create_piped_stdio(); exit_hook = 我们的回调
 *   do_mount(&procfs, "/proc"); do_mount(&devptsfs, "/dev/pts")
 *   task_run_current();                  // 进入客户机循环，不返回
 */

#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "kernel/init.h"
#include "kernel/fs.h"
#include "kernel/task.h"
#include "kernel/calls.h"
#include "fs/devices.h"
#include "fs/dev.h"
#include "fs/path.h"
#include "fs/real.h"
#include "fs/tty.h"

#ifndef CAPISH_MAX_PATH
#define CAPISH_MAX_PATH 4096
#endif

// 0=idle 1=booting 2=ready 3=failed
static volatile int g_capish_boot_state = 0;
static char g_capish_boot_error[512];

static void capish_fail(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_capish_boot_error, sizeof(g_capish_boot_error), fmt, ap);
    va_end(ap);
    g_capish_boot_state = 3;
}

// 打 stderr：iOS 上跑 autotest 时 stderr 会进 sim-console.log，CI 可以把带
// "capish:" 前缀的行抛成 annotation —— 崩到一半时这是唯一能定位的手段。
static void capish_log(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "capish: ");
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    fflush(stderr);
    va_end(ap);
}

int capish_boot_state(void) {
    return g_capish_boot_state;
}

const char *capish_boot_error(void) {
    return g_capish_boot_error;
}

// 客户机 init 退出时**不能** exit()：宿主 App 还要活着。
static void capish_exit_hook(struct task *task, int code) {
    (void) task;
    capish_fail("guest init exited (code=%d)", code);
}

static void make_dev_node(const char *path, int major, int minor) {
    int err = generic_mknodat(AT_PWD, path, (mode_t_) (S_IFCHR | 0666), dev_make(major, minor));
    if (err < 0) {
        // 已存在就不算错（重复 boot 时会撞上）
        fprintf(stderr, "capish: mknod %s failed: %d\n", path, err);
    }
}

static void *capish_boot_thread(void *arg) {
    char *root = (char *) arg;
    char source[CAPISH_MAX_PATH];
    int err;

    snprintf(source, sizeof(source), "%s/data", root);
    capish_log("boot start, fakefs root=%s", source);
    err = mount_root(&fakefs, source);
    capish_log("mount_root -> %d", err);
    if (err < 0) {
        capish_fail("mount_root(%s) failed: %d", source, err);
        goto out;
    }
    err = become_first_process();
    capish_log("become_first_process -> %d", err);
    if (err < 0) {
        capish_fail("become_first_process failed: %d", err);
        goto out;
    }
    if (current == NULL) {
        // 理论上不会，但真崩过的话至少能定位到这里
        capish_fail("current is NULL after become_first_process");
        goto out;
    }
    current->thread = pthread_self();

    // fakefs 下必须自己建这几个设备节点（xX_main_Xx 也这么做）
    make_dev_node("/dev/null", MEM_MAJOR, DEV_NULL_MINOR);
    make_dev_node("/dev/zero", MEM_MAJOR, DEV_ZERO_MINOR);
    make_dev_node("/dev/full", MEM_MAJOR, DEV_FULL_MINOR);
    make_dev_node("/dev/random", MEM_MAJOR, DEV_RANDOM_MINOR);
    make_dev_node("/dev/urandom", MEM_MAJOR, DEV_URANDOM_MINOR);
    make_dev_node("/dev/tty", TTY_ALTERNATE_MAJOR, DEV_TTY_MINOR);
    make_dev_node("/dev/console", TTY_ALTERNATE_MAJOR, DEV_CONSOLE_MINOR);
    make_dev_node("/dev/ptmx", TTY_ALTERNATE_MAJOR, DEV_PTMX_MINOR);

    static const char init_argv[] = "/bin/sh\0-l\0";
    static const char init_envp[] =
        "HOME=/root\0"
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\0"
        "TERM=xterm-256color\0";
    capish_log("exec /bin/sh");
    err = do_execve("/bin/sh", 2, init_argv, init_envp);
    capish_log("do_execve -> %d", err);
    if (err < 0) {
        capish_fail("do_execve(/bin/sh) failed: %d", err);
        goto out;
    }

    tty_drivers[TTY_CONSOLE_MAJOR] = &real_tty_driver;
    // exec 通道自带 pipe，所以 init shell 的 stdio 走 pipe 就够（不需要真终端）
    capish_log("create_piped_stdio");
    err = create_piped_stdio();
    capish_log("create_piped_stdio -> %d", err);
    if (err < 0) {
        capish_fail("create_piped_stdio failed: %d", err);
        goto out;
    }
    exit_hook = capish_exit_hook;

    do_mount(&procfs, "proc", "/proc", "", 0);
    do_mount(&devptsfs, "devpts", "/dev/pts", "", 0);

    g_capish_boot_state = 2;
    capish_log("boot ready, entering guest loop");
    // 进入客户机循环；只有客户机整个停掉才会返回
    task_run_current();
    capish_fail("guest loop returned");

out:
    free(root);
    return NULL;
}

/**
 * 启动客户机。rootfs_root 是 fakefs 的根目录（客户机的 / 映射到它下面的 data/）。
 * 异步：立即返回，boot 结果用 capish_boot_state() 轮询（2=ready）。
 */
int capish_boot(const char *rootfs_root) {
    if (g_capish_boot_state == 1 || g_capish_boot_state == 2) {
        return 0;
    }
    if (rootfs_root == NULL || rootfs_root[0] == '\0') {
        capish_fail("empty rootfs path");
        return -1;
    }
    char *copy = strdup(rootfs_root);
    if (copy == NULL) {
        capish_fail("out of memory");
        return -1;
    }
    g_capish_boot_state = 1;
    g_capish_boot_error[0] = '\0';

    pthread_t thread;
    if (pthread_create(&thread, NULL, capish_boot_thread, copy) != 0) {
        free(copy);
        capish_fail("pthread_create failed");
        return -1;
    }
    pthread_detach(thread);
    return 0;
}
