/*
 * capish-rootfs-ios.c —— 把离线的 `rootfs.tar`（gzip 的 Alpine aarch64 根文件树）解到
 * iSH 的 fakefs 根目录里，这样 `capish_boot()` 才有东西可引导。
 *
 * 为什么自己写：iOS 上没有 `tar` 命令，而 Foundation 也不解 tar。
 * 这里用 zlib 的 `gzopen/gzread`（iOS SDK 自带 libz）+ 一个最小 tar 解析器（512 字节头，
 * ustar 前缀也支持）。需要处理的类型：目录、普通文件、符号链接；其他（设备节点、fifo）
 * 由 iSH 自己在 boot 时建，直接跳过。
 *
 * 安全性：拒绝绝对路径和 `..`（tar 里的文件名是相对的；防止恶意包写到容器外）。
 */

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>

#define CAPISH_PATH_MAX 4096
#define TAR_BLOCK 512

static char g_capish_rootfs_error[512];
static int g_capish_rootfs_extracted = 0;

const char *capish_rootfs_error(void) {
    return g_capish_rootfs_error;
}

static void set_error(const char *fmt, const char *arg) {
    snprintf(g_capish_rootfs_error, sizeof(g_capish_rootfs_error), fmt, arg ? arg : "");
}

/** 递归建目录（dest 已存在不算错）。 */
static int mkdir_p(const char *path) {
    char tmp[CAPISH_PATH_MAX];
    size_t len = strlen(path);
    if (len == 0 || len >= sizeof(tmp)) return -1;
    memcpy(tmp, path, len + 1);
    for (size_t i = 1; i < len; i++) {
        if (tmp[i] != '/') continue;
        tmp[i] = '\0';
        if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
        tmp[i] = '/';
    }
    if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
    return 0;
}

/** 把 tar 里的相对路径接到 dest 后面；不合法（绝对路径 / ..）时返回 NULL。 */
static char *join_path(const char *dest, const char *rel) {
    if (rel[0] == '/' ) return NULL;
    if (strstr(rel, "../") != NULL || strcmp(rel, "..") == 0) return NULL;
    size_t need = strlen(dest) + strlen(rel) + 2;
    char *out = malloc(need);
    if (out == NULL) return NULL;
    snprintf(out, need, "%s/%s", dest, rel);
    return out;
}

static unsigned long long parse_octal(const char *field, size_t len) {
    unsigned long long value = 0;
    size_t i = 0;
    while (i < len && (field[i] == ' ' || field[i] == '\0')) i++;
    while (i < len && field[i] >= '0' && field[i] <= '7') {
        value = value * 8 + (unsigned long long) (field[i] - '0');
        i++;
    }
    return value;
}

int capish_extract_rootfs(const char *tar_gz_path, const char *dest_dir) {
    g_capish_rootfs_error[0] = '\0';
    if (tar_gz_path == NULL || dest_dir == NULL) {
        set_error("null argument%s", "");
        return -1;
    }
    if (mkdir_p(dest_dir) != 0) {
        set_error("mkdir_p(%s) failed", dest_dir);
        return -1;
    }

    gzFile gz = gzopen(tar_gz_path, "rb");
    if (gz == NULL) {
        set_error("gzopen(%s) failed", tar_gz_path);
        return -1;
    }

    unsigned char header[TAR_BLOCK];
    int rc = -1;
    long long written_total = 0;
    int entries = 0;

    while (gzread(gz, header, TAR_BLOCK) == TAR_BLOCK) {
        // 全零块 = 归档结束
        int all_zero = 1;
        for (int i = 0; i < TAR_BLOCK; i++) {
            if (header[i] != 0) { all_zero = 0; break; }
        }
        if (all_zero) break;

        char name[256];
        char prefix[256];
        char linkname[256];
        memcpy(name, header, 100); name[100] = '\0';
        memcpy(prefix, header + 345, 155); prefix[155] = '\0';
        memcpy(linkname, header + 157, 100); linkname[100] = '\0';
        unsigned long long size = parse_octal((const char *) header + 124, 12);
        unsigned long long mode = parse_octal((const char *) header + 100, 8);
        char type = (char) header[156];

        char rel[CAPISH_PATH_MAX];
        if (prefix[0] != '\0') {
            snprintf(rel, sizeof(rel), "%s/%s", prefix, name);
        } else {
            snprintf(rel, sizeof(rel), "%s", name);
        }
        // 去掉尾部的 '/'
        size_t rel_len = strlen(rel);
        while (rel_len > 0 && rel[rel_len - 1] == '/') rel[--rel_len] = '\0';
        if (rel_len == 0) continue;

        char *path = join_path(dest_dir, rel);
        int consumed = 0;
        if (path == NULL) {
            // 不安全的名字：跳过内容但保持流对齐
            goto skip_data;
        }

        if (type == '5') {
            mkdir_p(path);
            free(path);
            goto skip_data;
        }
        if (type == '2') {
            unlink(path);
            if (symlink(linkname, path) != 0) {
                // 目标已存在/平台限制：忽略
            }
            free(path);
            goto skip_data;
        }
        if (type == '0' || type == '\0') {
            // 保证父目录存在
            char parent[CAPISH_PATH_MAX];
            snprintf(parent, sizeof(parent), "%s", path);
            char *slash = strrchr(parent, '/');
            if (slash != NULL) { *slash = '\0'; mkdir_p(parent); }

            int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
            if (fd >= 0) {
                unsigned long long left = size;
                unsigned char buf[65536];
                int failed = 0;
                while (left > 0) {
                    size_t want = left < sizeof(buf) ? (size_t) left : sizeof(buf);
                    int got = gzread(gz, buf, (unsigned) want);
                    if (got <= 0) { failed = 1; break; }
                    if (write(fd, buf, (size_t) got) != got) { failed = 1; break; }
                    left -= (unsigned long long) got;
                    written_total += got;
                }
                close(fd);
                if (mode != 0) chmod(path, (mode_t) (mode & 07777));
                if (failed) {
                    set_error("short read while writing %s", rel);
                    free(path);
                    rc = -1;
                    goto out;
                }
            } else {
                // 打不开（例如只读项）就跳过内容，但要消费掉数据块
                set_error("open(%s) failed", rel);
            }
            free(path);
            entries++;
            consumed = 1;
            // 内容已在上面随读随消费，goto skip_data 时不要再读
        } else {
            free(path);
        }

    skip_data: {
            unsigned long long padded = (size + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK;
            // 普通文件的内容已经在上面随读随消费了；其余类型（目录/链接/跳过项）在这里对齐
            if (!consumed) {
                unsigned char scratch[4096];
                unsigned long long left = padded;
                while (left > 0) {
                    size_t want = left < sizeof(scratch) ? (size_t) left : sizeof(scratch);
                    int got = gzread(gz, scratch, (unsigned) want);
                    if (got <= 0) break;
                    left -= (unsigned long long) got;
                }
            }
        }
    }

    rc = 0;
    g_capish_rootfs_extracted = 1;
out:
    gzclose(gz);
    if (rc == 0) {
        printf("capish: rootfs extracted, %d entries, %.1f MB written\n",
               entries, (double) written_total / 1e6);
    }
    return rc;
}

/** 解包是否成功过（当前进程内）。 */
int capish_rootfs_extracted(void) {
    return g_capish_rootfs_extracted;
}
