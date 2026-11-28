# capacitor-linux

在 Capacitor App 里跑一个**真正的 Linux 虚拟机**：官方 Alpine 内核 + 官方 minirootfs，
由 QEMU（TCG 软件模拟）在 App 的沙箱里执行。内核、rootfs、QEMU 全部随 APK 离线分发，
**启动不需要网络**；客户机是独立进程，**Linux 崩溃不会连累 App**。

- Android：✅ 真机实测（Alpine 3.23.8 / kernel 6.18.65 / QEMU 11.1.3，arm64）
- iOS：❌ 暂不支持（引擎选型已确定：iSH-ARM64，见 [iOS 路线](#ios-路线ish-arm64目前不做但方案已经明确)）
- Web：❌ 不支持（会返回明确的 `UNSUPPORTED_PLATFORM`）

**最低兼容版本**：Android **7.0（API 24）+ arm64-v8a**；iOS 声明为 **15.0**（`Package.swift` 的
`platforms: [.iOS(.v15)]`，与 Capacitor 8 一致），但功能尚未实现。

- API 24 不是拍脑袋定的：随包的 QEMU 二进制自己的 `.note.android.ident` 里写的是
  `minSdkVersion = 24`（`llvm-readelf -n` 可查），而插件里所有 `Process#isAlive()` /
  `destroyForcibly()` / `waitFor(long, TimeUnit)` 这类 **API 26+** 的方法都已经换成兼容实现
  （`LinuxVm.isAlive()` / `waitForTimeout()` / 反射 `destroyForcibly`）。
- **只支持 arm64-v8a**：没有 32 位（armeabi-v7a）的 QEMU 可用，所以 Android 7 的 32 位机型
  （很老的设备）跑不了；插件会在 `getCapabilities()` 里如实返回 `supported:false` + 原因。
- 实测机是 Android 10（API 29），API 24/25 只有静态推导（没有相应真机），这点在
  [已知限制](#8-已知限制) 里也写了。

```ts
import { Linux } from 'capacitor-linux';

const caps = await Linux.getCapabilities();
if (!caps.supported) console.log(caps.reasons);

await Linux.addListener('consoleOutput', (e) => term.write(e.data)); // 流式 stdout
await Linux.addListener('consoleOutput', (e) => {
  if (e.stream === 'stderr') logs.write(e.data);                     // 流式 stderr（独立通道）
});

await Linux.start();                       // 引导 Alpine，真机约 17~19 秒
await Linux.write({ data: 'apk add python3\n' });   // 相当于在终端里敲键盘
const r = await Linux.exec({ command: 'python3 -V' }); // stdout/stderr 分开返回
await Linux.stop();
```

---

## 1. 六个需求的实现情况

| # | 需求 | 结论 | 实现方式 |
|---|---|---|---|
| 1 | 小巧的 Linux 发行版 | ✅ | **Alpine 3.23.8**（minirootfs 压缩后 4.0MB）。换成 Debian/Ubuntu 也能跑，代价是 rootfs 变大（见[换发行版](#换发行版)） |
| 2 | 流式 stdin/stdout/stderr | ✅ | 客户机控制台是真实的 PL011 串口（`/dev/ttyAMA0`），字节级流式；stderr 走第二条串口（`/dev/ttyAMA1`），所以两条流**不混**。不是"命令结束才返回" |
| 3 | 能操作运行中的进程 | ✅ | 控制台是真 tty，`write()` 的字节直接进 tty；`node` / `python` / `vi` 这种交互式程序都能正常收输入、实时出输出。配套 `sendKey({key:'ctrl+c'})` 发终端控制序列（Ctrl-C 会真的给前台进程组发 SIGINT） |
| 4 | 便于后续修改/扩展/升级 | ✅ | 版本全部固定在 `runtime.lock.json`；`scripts/fetch-linux-runtime.py` 一条命令重建运行时；`scripts/vm/init.sh` 是客户机 init（可直接改）；ELF 改名/打补丁、串口布局、QEMU 参数都在代码里有注释 |
| 5 | Linux 崩溃不连累 App | ✅ | QEMU 是**独立进程**；客户机 kernel panic → QEMU 退出 → 插件发 `vmExited`、状态变 `crashed`，App 继续运行（真机实测：panic 后重新 `start()` 立刻恢复） |
| 6 | Web 可以不做；Android/iOS 用原生、不要 wasm | ✅ 部分 | Android 原生（QEMU，非 wasm）；**iOS 未实现**，但引擎已经选定并验证可行（iSH-ARM64，AArch64 客户机，与 Android 用同一份 Alpine rootfs），见 [iOS 路线](#ios-路线ish-arm64目前不做但方案已经明确) |

另外两个后续追加的能力：

| 需求 | 实现 |
|---|---|
| **和 rootfs 交换文件** | `getShareDir()`：宿主目录 ↔ 客户机 `/mnt/host`（9p over virtio，双向、实时、不是拷贝）。见 [API](#46-getsharedir) |
| **rootfs 持久化** | `start({ rootfsMode: 'disk' })`：**整个 rootfs 落到一块 raw ext4 镜像**（App 私有目录里），客户机首次启动自己 `mkfs.ext4` + 从只读 tar 载荷解包 + `switch_root` 到磁盘 → `apk add` 的包、改过的文件、家目录全都保留（实测重启后 `file-5.46` 还在）。见 [rootfs 落盘](#47-rootfs-落盘--持久化start-rootfsmode-disk) |

### 为什么 Android 上"原生"只能是 QEMU 模拟

Android 10（API 29）起，`untrusted_app` 域在 SELinux 里**只允许 `execve` 标了 `apk_data_file` 的文件**，
也就是 `nativeLibraryDir`（APK 安装时解压的原生库目录）。App 自己解压到 `filesDir` 的 ELF
**不能 `execve`**（报 `EACCES`，而且因为 `dontaudit`，`dmesg`/logcat 里看不到拒绝记录）。
这就是 Termux/proot 一类方案必须把 `targetSdk` 停在 28 的原因。

QEMU 不受这条限制：客户机的指令由 QEMU 解释执行，**客户机里的程序从来不需要在 Android 上 `execve`**；
QEMU 自己在 `nativeLibraryDir` 里，`execve` 是允许的。附带好处是拿到了真正的内核隔离。

（真机实测：预编译的 Termux `qemu-system-aarch64-headless` 从 `nativeLibraryDir` 直接 exec 成功。）

---

## 2. 架构

```
┌──────────────────────────── WebView (JS) ────────────────────────────┐
│  Linux.start/write/exec  ·  consoleOutput / vmState / vmExited 事件   │
└───────────────────────────────┬──────────────────────────────────────┘
                     Capacitor 桥（JSON，事件 notifyListeners）
┌───────────────────────────────┴──────────────────────────────────────┐
│ LinuxPlugin（线程调度）  →  LinuxVm（生命周期 / 串口 / exec 协议）     │
│                                                                      │
│ ProcessBuilder ── execve ──► nativeLibraryDir/libqemu-system-aarch64.so
│        │                            │                                │
│        ├─ unix socket ttyAMA0.sock ─┤ 客户机 /dev/ttyAMA0 = 控制台     │
│        └─ unix socket ttyAMA1.sock ─┘ 客户机 /dev/ttyAMA1 = stderr 侧信道
└──────────────────────────────────────────────────────────────────────┘
                                     │
┌────────────────────────────────────┴─────────────────────────────────┐
│ QEMU (TCG, 独立进程)   -M virt -cpu max -m 1024 -smp 2               │
│   kernel = assets/linux/kernel（Alpine vmlinuz-virt 6.18.60）         │
│   rootfs = assets/linux/rootfs.cpio（离线 initramfs，6.3MB，含 e2fsprogs）│
│   网络   = -netdev user (SLIRP)  → 客户机 10.0.2.15/24，NAT 到宿主     │
│                                                                      │
│   Alpine 3.23.8：busybox + musl + apk + openrc，writable tmpfs root   │
│   /init：挂 proc/sys/dev → insmod 5 个 virtio 模块 → 配 SLIRP 网卡     │
│          → 打印 "capinux: ready" → 循环保持 root shell（setsid -c）    │
└──────────────────────────────────────────────────────────────────────┘
```

**为什么 rootfs 用 initramfs（cpio.gz）而不是磁盘镜像**：内核直接把 cpio 解到 RAM 里当 rootfs，
不需要 `mkfs`、不需要 loop 设备、不需要任何 root 权限，构建侧也不用 Linux 工具链
（构建脚本是纯 Python，Windows 上也能跑）。代价是**重启即还原**（见[已知限制](#8-已知限制)）。

**为什么两个串口**：一个 tty 上 stdout/stderr 天然合并（跟真实终端一样）。为了满足"stdout/stderr
分别流式返回"，第二条 16550 串口专门承载 stderr：`exec()` 把命令的 fd 2 重定向到 `/dev/ttyAMA1`，
插件从对应 socket 读取，两条流各带唯一标记，互不污染。

---

## 3. 安装与构建

### 3.1 宿主 App 必须做的事

1. **开启原生库解压**（否则 `nativeLibraryDir` 里没有 QEMU 可执行文件，`start()` 会返回
   `ENGINE_MISSING`）：

   ```gradle
   // android/app/build.gradle
   android {
       packaging {
           jniLibs {
               useLegacyPackaging = true   // -> AndroidManifest 的 android:extractNativeLibs="true"
           }
       }
   }
   ```

2. **生成运行时资源**（33MB 下载一次，产物不入库）：

   ```bash
   cd plugins/capacitor-linux
   python3 scripts/fetch-linux-runtime.py     # 产物在 plugins/capacitor-linux/runtime/
   ```

3. 然后照常构建：

   ```bash
   npm run build:plugins     # 本仓库约定：建 App 前先建插件
   npx cap sync android
   npm run android:deploy -- -Device <serial>
   ```

### 3.2 运行时资源是怎么来的（`scripts/fetch-linux-runtime.py`）

| 产物 | 来源 | 校验 |
|---|---|---|
| `assets/linux/kernel` | Alpine `linux-virt` 6.18.65 apk 里的 `boot/vmlinuz-virt`（10.4MB） | sha256 固定 |
| `assets/linux/rootfs.cpio` | Alpine 官方 `alpine-minirootfs-3.23.8-aarch64.tar.gz` + `scripts/vm/init.sh` + 13 个 `.ko` + e2fsprogs 等 6 个 `.apk` 的内容 | 构建后跑 `cpio-lint.py` 自检 |
| `jniLibs/arm64-v8a/libqemu-system-aarch64.so` | Termux 的 `qemu-system-aarch64-headless` 11.1.3（bionic/arm64 构建） | 仓库 index 里的 SHA256 |
| `jniLibs/arm64-v8a/lib*.so` | 上述 QEMU 的 `DT_NEEDED` 传递闭包（共 69 个库） | 逐个解析 ELF 校验，缺一个就报错 |

脚本里有两个**踩过的坑**，改脚本前务必知道：

1. **`jniLibs` 只打包 `lib*.so` 名字的文件**。`libz.so.1`、`libglib-2.0.so.0` 这类带版本号的库会被
   AGP **静默丢掉**，APK 里没有、运行时 `CANNOT LINK EXECUTABLE: library "libz.so.1" not found`。
   解决：`jni_name()` 把 `X.so.N` **同长度**改名为 `X_N.so`（`libz.so.1` → `libz_1.so`），
   然后用 `elf_rename_deps()` 就地改写所有 ELF 的 `DT_NEEDED`/`DT_SONAME` 字符串
   （长度不变才能原地改，否则会破坏动态段）。
2. **AAPT 会把 assets 里 `*.gz` 的扩展名去掉**（`rootfs.cpio.gz` → `rootfs.cpio`）。所以产物就叫
   `rootfs.cpio`（内容仍是 gzip，内核按 magic 识别），并且 Java 侧**从 manifest 读资产名**，
   不写死文件名。

另外 `build-initramfs.py` 必须按"目录 → 符号链接 → 文件"的顺序写 cpio：内核的 initramfs 解包器
**不跟随符号链接**，如果某个条目比它的父目录先出现、而父目录后面又是符号链接（Alpine 的 `/lib` 就是），
那个子树会被**静默丢弃**——表现为"APK 里有文件、客户机里没有"。`cpio-lint.py` 就是为这个坑写的，
`fetch-linux-runtime.py` 每次构建都会跑它，有问题直接失败。

---

## 4. API

所有方法都是 Promise。事件用 `addListener`（Capacitor 桥内置）。

### 4.1 `getCapabilities()`

```ts
interface LinuxCapabilities {
  platform: 'android' | 'ios' | 'web';
  supported: boolean;              // false 时看 reasons
  engine: 'qemu-tcg' | 'none';
  architecture: string;            // 'aarch64'
  nativeExecution: boolean;        // false：客户机是模拟执行
  isolation: 'process' | 'none';   // process = 崩溃不影响 App
  apkAvailable: boolean;           // 客户机自带 apk 包管理器
  persistence: 'none' | 'disk';    // 当前 none：重启还原
  minimum: {
    androidSdk?: number;           // 24
    abis?: string[];               // ['arm64-v8a']
    deviceRamMb?: number;          // 建议 2048（VM 默认占 1GB）
    webViewMajor?: number;
  };
  reasons: string[];
}
```

### 4.2 `start(options?)` → `LinuxStatus`

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `memoryMb` | number | 1024 | 客户机内存，256~4096 |
| `cpus` | number | 2 | 客户机 vCPU 数，1~8 |
| `kernelCmdline` | string | – | 追加到内核命令行（默认 `console=ttyAMA0 rdinit=/init loglevel=6 panic=1`） |
| `bootTimeoutMs` | number | 180000 | 等 `capinux: ready` 的超时；超时会杀 QEMU 并 reject `BOOT_TIMEOUT` |

首次 `start()` 会先把 kernel/rootfs 解压到 `files/linuxvm/alpine/`（约 15MB，几百毫秒，
用 sha256 戳记判断是否需要重新解压，所以升级运行时后会自动重解压）。

### 4.3 `stop({ force? })` / `restart(options?)`

`stop()` 先往控制台写 `poweroff -f`，8 秒不退出就杀进程；`force: true` 直接杀。
（`stop()` 走独立的 I/O 线程，不会排在正在阻塞的 `exec()` 后面。）

### 4.4 `write({ data, encoding? })`

往客户机 stdin 写字节。`encoding: 'utf8' | 'base64'`（默认 utf8，base64 可传二进制）。
这就是"键盘输入"：`write({ data: 'ls\n' })`、给 `read`/`input()`/REPL 喂数据都靠它。

#### 特殊键：`sendKey({ key, repeat? })`

终端控制序列集中在一处，等价于 `write({ data: '\u0003' })` 但更好用：

```ts
await Linux.sendKey({ key: 'ctrl+c' });          // 0x03 -> 客户机 tty 给前台进程组发 SIGINT
await Linux.sendKey({ key: 'ctrl+d' });          // 0x04 -> EOF
await Linux.sendKey({ key: 'up', repeat: 3 });   // ESC[A x3（xterm 序列）
await Linux.sendKey({ key: 'tab' });             // 命令补全
```

| 分组 | 键名 |
|---|---|
| 控制键 | `ctrl+a` … `ctrl+z`（终端控制字符：Ctrl-C=0x03、Ctrl-D=0x04、Ctrl-Z=0x1a、Ctrl-L=0x0c…） |
| 编辑键 | `enter`(CR)、`tab`、`shift+tab`、`backspace`(0x7f)、`escape`、`space` |
| 光标/翻页 | `up` `down` `left` `right` `home` `end` `pageup` `pagedown` `insert` `delete` |
| 功能键 | `f1` … `f12` |

- 键名大小写不敏感；还接受别名 `return`/`esc`/`pgup`/`pgdn`/`del`/`ins`。
- `repeat` 默认 1，最大 100（多次发送之间隔 30ms，避免 TUI 里被合并成一次按键）。
- 键名清单也导出成 `LINUX_KEY_NAMES` / `LINUX_COMMON_KEYS`（`import { LINUX_COMMON_KEYS } from 'capacitor-linux'`），方便做键盘条。
- 真机实测：`sleep 25` 运行中发 `ctrl+c` → 立即回到提示符（`DONE-9` 不再打印，符合 shell 语义）；
  若被中断的是 `exec()` 的进程，`exec()` 会立刻返回 `{ code: 130, interrupted: true }`，不再干等超时。
- ⚠️ 为了 Ctrl-C 之后控制台还能继续用，客户机里的交互 shell **关闭了 job control**
  （`/etc/profile.d/00-capinux-nojc.sh` 里的 `set +m`，`/init` 还有一层看门狗）。代价：
  **没有 Ctrl-Z / `fg` / `bg`**。原因见[已知限制](#8-已知限制)。

### 4.5 `exec({ command, timeoutMs? })` → `LinuxExecResult`

```ts
interface LinuxExecResult {
  code: number;       // 退出码（超时为 -1）
  stdout: string;     // 标准输出（ttyAMA0）
  stderr: string;     // 标准错误（ttyAMA1，独立通道）
  durationMs: number;
  timedOut: boolean;  // 超时会先发 Ctrl-C
}
```

实现：命令 **base64 编码**后写成客户机里的临时脚本 `/tmp/.capinux-exec-<id>.sh`，再
`sh 脚本 >/dev/ttyAMA0 2>/dev/ttyAMA1`，两端各自用 `\x1f B/E <id>` 标记包围，
插件按标记切出两条流。因此**命令里有引号、换行、管道都安全**。

要点：

- 同一时刻只允许一个 `exec()`（控制台只有一个前台进程）；
- 命令输出**同时**会出现在 `consoleOutput` 事件里（它确实打在控制台上）；
- 需要和进程**多轮交互**（REPL、`read`、`vi`）时，用 `write()` + `consoleOutput`，
  或者像示例那样先起 `exec()` 再 `write()`（两者现在可以并行：写入不会被阻塞）。

### 4.6 `getShareDir()`

宿主与客户机之间的**双向共享目录**（9p over virtio：QEMU `-virtfs` + 客户机
`9pnet_virtio`/`9p` 模块）。rootfs 本体在内存里，宿主上并没有一份可写的 rootfs 目录，
所以"和 Linux 交换文件"用这个通道：

```ts
const share = await Linux.getShareDir();
// { hostPath: '/data/user/0/<pkg>/files/linuxvm/share', guestPath: '/mnt/host', ... }

// 宿主 -> 客户机：用 @capacitor/filesystem / capacitor-native-files 往 hostPath 写文件后
await Linux.exec({ command: 'cp /mnt/host/config.json /root/ && cat /root/config.json' });

// 客户机 -> 宿主：客户机里写到 /mnt/host，宿主直接读 hostPath
await Linux.exec({ command: 'echo hello > /mnt/host/out.txt' });
```

返回字段：`hostPath`（宿主绝对路径，目录会自动创建）、`guestPath`（固定 `/mnt/host`）、
`exists`、`available`（VM 是否在跑）、`hostEntries`（宿主目录条目数）、`note`（排错提示）。

真机实测：宿主写入 `from-host.txt` → 客户机 `cat /mnt/host/from-host.txt` 读到；
客户机 `echo ... > /mnt/host/from-guest.txt` → 宿主 19 字节内容一致。

> 如果 `/mnt/host` 是空的：客户机里 `mount | grep 9p` 看有没有挂载；没有就是 9p 模块没加载
> （内核包和模块版本不匹配，见[升级 Alpine](#升级-alpine例如到-324x)）。

### 4.7 rootfs 落盘 / 持久化（`start({ rootfsMode: 'disk' })`）

默认是 `rootfsMode: 'ram'`：内核把 `rootfs.cpio` 解到内存当根，启动快（实测 **~25s**），
但重启即还原。改成 `'disk'`，**整个 rootfs 就落到一块磁盘镜像上**，`apk add` 的包也留得住：

```ts
await Linux.start({ rootfsMode: 'disk', diskSizeMb: 2048 });   // 首次启动约 1 分钟
await Linux.exec({ command: 'apk add --no-cache file' });       // 装到 /usr（在磁盘上）
await Linux.stop();
await Linux.start({ rootfsMode: 'disk', diskSizeMb: 2048 });   // 约 45s
await Linux.exec({ command: 'file --version' });                // ✅ 还在（真机实测）
```

它是怎么做的（这也是"能不能把 rootfs 解压到 App 可写目录再启动"的答案）：

1. App 在私有目录创建一个 **sparse raw 镜像**（`files/linuxvm/disk.img`，默认 2048MB）；
2. QEMU 把它挂成 `/dev/vda`，另外把 `assets/linux/rootfs.tar`（**同一份离线 rootfs 的 tar 版**）
   以只读方式挂成 `/dev/vdb`；
3. initramfs 里的 `/init` 换成 **`init-bootstrap.sh`**：加载 virtio_blk + ext4 模块 →
   已有文件系统就直接挂，没有就 `mkfs.ext4`（e2fsprogs 是构建期 bake 进 rootfs 的）→
   `gzip -dc /dev/vdb | tar -x -C /mnt` → **`switch_root` 到磁盘**；
4. 切过去之后跑的是磁盘里的 `/init`（就是 `scripts/vm/init.sh`），`/` 已经是 `/dev/vda`，
   所以 `/usr`、`/lib`、`/etc`、`/root` 全都是持久的。

实测（Redmi K30S）：首次启动 **~61s**（含 mkfs + 解包 15.7MB），之后 **~36–48s**；
`df -h /` → `/dev/vda 1.9G ... /`；重启后 `apk add file` 装的 `file-5.46`、手写的
`/usr/bin/capinux-test` 都还在。

为什么是"块设备 + ext4"而不是"直接拿宿主目录当根"：客户机要的是一个**真实文件系统**，
QEMU 通过 virtio-blk 提供；宿主目录那条路是 9p（见 [`getShareDir()`](#46-getsharedir)），
可以做共享但作为根会很慢、也不支持完整的 POSIX 语义（设备节点、权限、hardlink 等）。

`persistent: true` 是旧写法，等价于 `rootfsMode: 'disk'`（早期版本只把 `/root`、`/home`、
`/opt`、`/usr/local` bind 到数据盘上，包不持久；现在被整盘模式取代）。

### 4.8 事件

| 事件 | payload | 说明 |
|---|---|---|
| `consoleOutput` | `{ data, stream: 'stdout' \| 'stderr' }` | 控制台流式输出；`stream` 区分 stdout（ttyAMA0）与 stderr（ttyAMA1） |
| `vmState` | `{ state, detail }` | `stopped/starting/running/stopping/crashed/error` |
| `vmExited` | `{ code, uptimeMs, requested, reason }` | 进程退出；`requested:false` 即异常退出（含 kernel panic） |
| `engineLog` | `{ line }` | QEMU 自己的 stderr（排错用），同时打进 logcat（tag `CapacitorLinux`） |

### 4.9 `getInfo()` / `getStatus()`

`getInfo()` 返回发行版/内核/QEMU 版本、各文件字节数、可执行文件路径与存在性、是否已解压；
`getStatus()` 返回 `state/pid/uptimeMs/exitCode/memoryMb/cpus/persistence/shareDir/guestShareDir/diskBytes/lastError`。

---

## 5. 客户机里有什么

Alpine 3.23.8 的 minirootfs：busybox（含 `sh`、`vi`、`wget`、`nc` 等 applet）、musl、
`apk` 包管理器（apk-tools 3.0.9）、openrc、`/etc/apk/keys`（官方签名公钥）。

- `/init`（`scripts/vm/init.sh`）：PID 1。挂 proc/sys/dev/devpts，`insmod` **13 个内核模块**
  （`virtio_mmio/net/blk`、`netfs + 9p*`、`crc16 + mbcache + jbd2 + ext4`，全部来自同一个 `linux-virt`
  包），日志用 `mkfs.ext4` 来自 bake 进去的 `e2fsprogs`；然后挂 9p 共享目录、按需挂持久化数据盘，
  后台配 SLIRP 网卡，打印 `capinux: ready`，最后 `setsid -c sh -l` 循环保持 root shell。
- 网络：QEMU SLIRP，客户机固定 `10.0.2.15/24`、网关 `10.0.2.2`；DNS 指公共解析器
  （`1.1.1.1` / `8.8.8.8`）——**slirp 自带的 `10.0.2.3` 在 QEMU 11 上不响应**，实测会超时。
- `apk add` 真的能用（真机实测 `apk add python3` → 33 个包 / 50.2MiB，`python3 -c "print(2**10)"` → 1024）。

---

## 6. 维护与升级（给后来的人/AI）

### 文件地图

```
plugins/capacitor-linux/
├─ runtime.lock.json               ← 所有第三方版本 + sha256 都在这（改这里）
├─ scripts/
│  ├─ fetch-linux-runtime.py       ← 下载 + 校验 + 组装 runtime/（唯一入口）
│  ├─ build-initramfs.py           ← minirootfs + init + 模块 → cpio.gz
│  ├─ cpio-lint.py                 ← 按内核解包规则检查 cpio（防"静默丢文件"）
│  └─ vm/
│     ├─ init.sh                   ← 客户机 /init（PID 1，可直接改）
│     └─ udhcpc.sh                 ← DHCP 回退钩子
├─ android/src/main/java/com/testionic/linux/
│  ├─ LinuxPlugin.java             ← Capacitor 方法/事件、线程调度（exec/start 走串行 worker，write/stop 走 I/O worker）
│  └─ LinuxVm.java                 ← QEMU 进程、双串口、exec 协议、资产解压、崩溃检测
├─ ios/Sources/LinuxPlugin/LinuxPlugin.swift  ← 目前只返回 UNSUPPORTED_PLATFORM
└─ src/{definitions.ts,index.ts,web.ts}       ← TS 类型与 web 桩
```

### 升级 Alpine（例如到 3.24.x）

1. 改 `runtime.lock.json` 的 `alpine.kernel`（`version`/`url`/`sha256`/`bytes`/`release`）、
   `alpine.version`、`releaseBase`、minirootfs 的 `sha256`/`bytes`，以及 `persist.packages` 里
   e2fsprogs 那一组版本号；
2. `python3 scripts/fetch-linux-runtime.py`（重新下载并按新 sha256 校验）；
3. 看脚本输出的 `cpio-lint` 结果和模块列表（`modules` 的路径要跟着内核版本走）；
4. `npm run build:plugins && npx cap sync android && npm run android:deploy`；
5. 真机跑一次 `autotest`，确认 `uname -r`、`apk`、`/mnt/host`、持久盘都正常。

> **内核与模块必须来自同一个包**：模块带 vermagic，跨内核版本 `insmod` 会失败，
> 表现是客户机**没有网卡**（`ip addr` 只有 `lo`）、`/mnt/host` 空、或持久盘挂不上。
> 所以内核不再用 netboot 的 `vmlinuz-virt`，而是和模块一起从 `linux-virt` apk 里取。
> 另外 Alpine 的 `main` 仓库只保留最新版本，锁里的 `sha256` 校验失败就是在提醒升级。

### 升级 QEMU

改 `runtime.lock.json` 的 `qemu.version`，重建。脚本会用 Termux 仓库 index 里的
`SHA256` 校验每个 `.deb`，再解析新二进制的 `DT_NEEDED` 闭包——**新版本如果多依赖了某个库，
闭包里会自动出现**，不用手工维护库列表。

### 换发行版

`initramfs` 的做法对任何"minirootfs + init 脚本"的发行版都成立，只要：

- 提供 arm64 内核（`-kernel` 需要能引导 `virt` 机器，通常要用各发行版的 `virt`/`generic` 内核）；
- 提供 tar 形式的 rootfs（Debian: `debootstrap` 产物；Ubuntu: cloud image 的 rootfs tar）；
- 客户机里要有 `insmod` 和 `sh`（或改 `scripts/vm/init.sh` 用别的方式加载模块/起 shell）。

Debian/Ubuntu 的 rootfs 会从 4MB 涨到 60~120MB，APK 相应变大，其余不变。

### 可能想加的东西（按性价比）

1. **整机持久化（root 也落盘）**：现在是 initramfs 根 + 持久盘只绑 `/root`、`/home`、`/opt`、
   `/usr/local`，所以 `apk add` 的包重启要重装。做法：持久盘上放一份完整 root，
   `/init` 里 `mount -t overlay overlay -o lowerdir=/,upperdir=<disk>/upper,workdir=<disk>/work`
   再 `switch_root`（或者干脆 `root=/dev/vda1` 直接启动）。`ext4`/`jbd2` 模块已经在包里了；
2. **多控制台**：再开 `-chardev socket` + `-serial`，每个 socket 一个会话（现在的代码已经是按
   "socket = 一路流"写的，加一路就是多一个文件 + 一个 pump）；
3. **端口转发**：`-netdev user,hostfwd=tcp:127.0.0.1:8080-:8080`，让宿主直接访问客户机起的
   HTTP 服务（现在只能客户机出网）；
4. **减小体积**：现在的 QEMU 闭包 68.8MB，里面 audio/spice/curl/ssh 基本用不到；
   自己编一个 `--disable-*` 的 QEMU 能砍到 ~15MB（但需要 Linux 构建环境，且要重新验证）；
5. **恢复 job control**：需要上游 busybox/ash 在 Ctrl-C 之后正确 `tcsetpgrp` 回自己，
   或者在 `/init` 里用一个真正的 getty（`setsid` + `TIOCSCTTY` + `tcsetpgrp` 全做一遍）。

---

## 7. iOS 路线（iSH-ARM64）—— 第 1 步已落地

Apple 不允许 JIT，也不允许 `fork`/`exec` 子进程，所以 Android 那套"QEMU + 独立进程"在 iOS 上
不成立。iOS 的引擎选的是 **[iSH-ARM64](https://github.com/everettjf/dsh-ios)**（`ish-app/ish` 的
fork，源码在 `dsh-ios` 仓库的 `ish-arm64/`）：Asbestos 线程码解释器（不生成机器码）+ **AArch64
客户机后端**，所以客户机和 Android 一样是 **arm64 Alpine**；48 位地址空间、NEON、futex/epoll
都有，已经在 OpenMinis 里以"Agent Shell Sandbox"上线。

### 已经做好的部分

| 部分 | 状态 |
|---|---|
| 引擎桥 `ios/Sources/CapISHBridge/` | ✅ C 接口（`capish_exec` / `capish_kill` / `capish_available`），用 `__has_include("ISHShellExecutor.h")` **两路编译**：有引擎调 `ISHShellExecutor`，没有就编成桩 → **没引擎也能编译**，iOS CI 不会红 |
| `LinuxPlugin.swift` | ✅ `getCapabilities`（`engine: ish-arm64`、`isolation: none`）、`start/stop/restart`、`exec`（逐行 streaming + stdout/stderr 分流 + 退出码）、`getShareDir`（暂时返回宿主 Documents） |
| `scripts/fetch-ish-ios-runtime.sh` | ✅ **只能在 macOS 上跑**：sparse clone 固定 commit → 用 **meson**（iSH 自己的构建方式，`default_library=static`）分别编 iphoneos / iphonesimulator 的 `libish.a`+`libish_emu.a`+`libfakefs.a` → 再单独编 `app/ISHShellExecutor.m` → `libtool` 合并 → `xcodebuild -create-xcframework` 产出 `ios/CapISH.xcframework`。失败会把关键行抛成 `::error::` annotation |
| `.github/workflows/ios-ish.yml` | ✅ **workflow_dispatch 手动触发**（90 分钟上限）：跑上面的脚本，把 xcframework 当 artifact 传上来 |

### 还没做 / 下一步（按顺序）

1. **真正跑一次构建**：`gh workflow run ios-ish.yml`（或网页 Actions 点 Run）。本会话没有 Mac，
   这一步只能在 CI / 你本机的 Xcode 上迭代；脚本用 `ISH_DEBUG=1` 打印每条编译命令，失败会直接
   `cat` 对应日志，第一处 `error:` 就是要修的地方。
2. **链接进宿主 App**：把 `ios/CapISH.xcframework` 加进 App target，`HEADER_SEARCH_PATHS` 加上
   `ios/ish-headers`（脚本末尾会打印精确步骤）。链接成功后 `getCapabilities().supported`
   会自动变成 `true`（`ISHShellExecutor` 类存在了）。
3. **交互式 stdin**：`ISHShellExecutor` 只有命令级执行，没有长驻 shell 的 stdin 通道，所以现在
   iOS 的 `write()` / `sendKey()` 明确返回 `UNSUPPORTED_ON_IOS`。下一步接 iSH 的 real tty
   （`ish-arm64/fs/dev.c` 的 tty 设备 + `openpty`）。
4. **共享目录**：iSH 支持 bind mount，接上之后 `getShareDir()` 才能给出 `guestPath`。
5. **崩溃隔离弱于 Android**：iSH 的客户机跑在 App 进程内（它自己模拟 fork），客户机里的段错误
   有可能带走 App —— 最终文档不能照抄 Android 的"崩溃不连累 App"。

许可证：iSH 上游 **GPLv3**（`ish-arm64/LICENSE.md`，另有 `LICENSE.IOS` 附加条款），商用前要确认义务。

---

## 8. 已知限制

| 限制 | 说明 / 影响 | 缓解 |
|---|---|---|
| **默认 rootfs 在内存里** | `rootfsMode:\\'ram\\'`（默认）重启还原；想连包一起持久化就用 **`rootfsMode:\\'disk\\'`**（整盘落盘，实测有效），代价是首次启动 ~61s、之后 ~40s、占 `diskSizeMb` 存储 | 见 [4.7](#47-rootfs-落盘--持久化start-rootfsmode-disk) |
| **没有 job control** | 客户机交互 shell 关了 `set +m`（否则 Ctrl-C 之后 tty 的前台进程组会指向已死的作业，shell 卡在读输入，控制台像死了） | 代价是**没有 Ctrl-Z / `fg` / `bg`**；`Ctrl-C`、`Ctrl-D`、`Ctrl-L` 都正常 |
| **速度** | QEMU TCG 纯模拟，CPU 约慢 3~5 倍（与 Linxr 论文报的 ~3× 一致）；`apk add python3` 约 2 分钟 | 交互式 shell/脚本够用；重 CPU 任务别放进来 |
| **内存** | 客户机默认 1GB，QEMU 自己的 RSS 也在这个量级；低端机可能被系统杀 | `memoryMb: 512`；真机建议 2GB 以上空闲内存 |
| **包体积** | QEMU 二进制 29.5MB + 依赖库 37.8MB + kernel 10.4MB + rootfs 6.3MB ≈ **85MB**（APK 压缩后增加约 60MB） | 见 [6.4](#可能想加的东西按性价比) 的裁剪方案 |
| **网络** | 客户机只有 SLIRP：能出网（NAT）、能 DNS；**外部不能直连客户机端口**（要 `hostfwd`，暂未暴露 API） | 需要就加 `-netdev user,hostfwd=...` 参数（在 `LinuxVm.start()` 里） |
| **没有 GPU / X11** | 只能跑命令行程序 | 要图形得加 virtio-gpu + 远端桌面，工作量大 |
| **iOS** | 引擎桥 + 构建脚本 + CI 任务已就绪，但**还没在 macOS 上编译过**，`write()/sendKey()` 未实现（iSH 没有长驻 shell stdin） | 见第 7 节；先跑 `ios-ish.yml` 产出 xcframework |
| **Web** | 不支持（返回明确原因） | 需要 wasm 版 QEMU/v86，不在本插件范围 |
| **API 24/25 未真机验证** | minSdk 是 24、相关 API 26+ 方法都换成了兼容实现，但手头只有 Android 10 的机器 | 需要一台 7.x 机器跑一次 autotest；CI 里没有 Android 设备 |
| **PID 1 不回收孤儿子进程** | `/init` 用 `wait` 只等直接子进程；客户机里 daemonize 出来的进程退出后会留僵尸 | 长期跑 daemon 的场景需要 `init` 换成 openrc/tini |
| **QEMU 的 `panic=1`** | 内核 panic 后由 QEMU 退出（`-no-reboot`），我们据此判定 `crashed`；如果客户机只是"卡住"（不 panic 也不退出），插件不会自动发现 | 需要的话加看门狗（定时执行 `echo alive` 探测） |

---

## 9. 实测记录（Redmi K30S / Android 10 / MIUI 12 / arm64，2026-11-28）

| 项 | 结果 |
|---|---|
| 引擎 | QEMU 11.1.3（Termux bionic 构建），从 `nativeLibraryDir` 直接 exec 成功 |
| 引导 | `start()` → `capinux: ready` **17.0~26.1 秒**（TCG，2 vCPU / 1GB；开持久盘时多几秒做 mkfs） |
| 发行版 | `cat /etc/alpine-release` → `3.23.8`；`uname -r` → `6.18.65-0-virt` |
| apk | `apk --version` → `apk-tools 3.0.9-r0`；`apk update` → 27445 个包；`apk add python3` → 33 包 / 50.2MiB；`python3 -c "print(2**10)"` → `1024` |
| exec | `exec()` 返回 `code:0`，stdout 有输出，stderr 独立（`echo X >&2` 只出现在 stderr 字段） |
| 交互 stdin | `exec({command:'read name; echo "HELLO-$name"'})` + 写入 → `HELLO-from-webview`，`code:0`，3.2 秒完成 |
| 特殊键 | `sleep 25` 运行中 `sendKey({key:'ctrl+c'})` → 立即回到 `capinux:/#`，`DONE-9` 未打印（SIGINT 生效） |
| 共享目录 | 宿主写 `from-host.txt` → 客户机 `cat /mnt/host/from-host.txt` 得到内容；客户机写 `/mnt/host/from-guest.txt` → 宿主 19 字节一致；`mount` 显示 `hostshare on /mnt/host type 9p` |
| 持久化 | `start({persistent:true, diskSizeMb:512})` → `/dev/vda on /root type ext4`（另含 /home /opt /usr/local）；写入 `/root/keep.txt` → **stop + start 后 `cat` 仍是 `PERSISTED-42`** |
| 崩溃隔离 | `echo c > /proc/sysrq-trigger` → 客户机 kernel panic → QEMU 退出（`vmExited {requested:false}`）→ 插件状态 `crashed`，**App 存活** |
| 恢复 | panic 后再 `start()` → 18.4 秒起来，`exec` 正常 |
| 文件系统 | `ls /` → `bin dev etc home init lib ...`（Alpine 目录结构，`/init` 是我们自己的） |
| 内核模块 | `virtio_mmio/net/blk`、`netfs + 9p*`、`crc16 + mbcache + jbd2 + ext4` 共 13 个，全部来自 `linux-virt` 同一个包（版本天然匹配） |

调试期踩过的坑（都写进代码注释了）：

1. 就绪标记 `capinux: ready` 可能被读成两块 → 必须用**滚动缓冲**匹配，不能只看当前 chunk；
2. `-serial stdio` 在管道下输入会被"攒到大下一次写入"，换成 **unix socket chardev**；
3. `write()` 和 `exec()` 原来共用一个**单线程** worker → exec 阻塞时 stdin 写不进去
   （表现像"read 收不到输入"），拆成 I/O 线程池后正常；
4. `exec` 的结束标记正则漏了 `:`（guest 输出 `\x1fE<id>:<code>`），导致永远匹配不上；
5. 客户机 PID 1 **忽略 SIGKILL**，测试崩溃要用 sysrq，不能用 `kill -9 1`；
6. `jniLibs` 丢版本号库、AAPT 去掉 `.gz`、cpio 里符号链接顺序 —— 见第 4 节；
7. **busybox ash 的 job control 在这个 tty 上会在 Ctrl-C 后把前台进程组留在死作业上**，
   shell 随后 `read` 触发 SIGTTIN 被挂起 → 关掉 `set +m` + `/init` 看门狗；
8. Windows 上 `.apk` 里的符号链接创建不了（`WinError 1314`）、POSIX 执行位丢失
   （`mkfs.ext4` 变成 0666 跑不了）→ 用 `.capinux-symlinks.json` / `.capinux-modes.json` 记录后
   在 cpio 里还原。

---

## 10. 许可证与来源

随 APK 分发的二进制：

| 组件 | 许可证 | 来源 |
|---|---|---|
| QEMU 11.1.3（含 `qemu-system-aarch64-headless` 及其依赖库） | **GPLv2** | Termux 官方仓库 `packages.termux.dev`（bionic/arm64 构建） |
| Alpine 内核 `linux-virt` 6.18.65 / minirootfs / 内核模块 | 各包自己的许可证（多为 MIT/Apache-2.0/GPL） | `dl-cdn.alpinelinux.org`，Alpine 官方签名 |
| `e2fsprogs` / `e2fsprogs-libs` / `libblkid` / `libuuid` / `libeconf` / `libcom_err` | GPL-2.0 / LGPL / BSD 等（见各包） | 同上，用于客户机里 `mkfs.ext4` |

**注意 GPLv2 义务**：分发包含 QEMU 的 APK 时，必须能提供对应源码（Termux 的
`qemu-system-*` 构建配方在 `termux/termux-packages`，QEMU 上游源码在 qemu.org）。
插件代码本身是 MIT，但**整个 APK 因为包含 QEMU 而受 GPLv2 约束**——这是选这个方案时必须知道的事。

---

## 11. 排错

| 现象 / 错误码 | 原因 | 处理 |
|---|---|---|
| `ENGINE_MISSING` | `nativeLibraryDir` 里没有 `libqemu-system-aarch64.so` | 宿主 App 没开 `useLegacyPackaging = true`，或没跑 `fetch-runtime` |
| `RUNTIME_MISSING` | APK 里没有 `assets/linux/manifest.json` | 同上：先 `python3 scripts/fetch-linux-runtime.py`，再 `build:plugins` |
| `BOOT_TIMEOUT` | 180 秒内没等到 `capinux: ready` | 看 `engineLog` / logcat（tag `CapacitorLinux`）；客户机内核与模块版本不匹配时也会这样 |
| `VM_NOT_RUNNING` | 没有 `start()` 就 `exec/write` | 先 `start()` |
| `CANNOT LINK EXECUTABLE: library "libX.so" not found`（logcat） | jniLibs 里缺库（版本号命名问题） | 重新跑 `fetch-runtime`（脚本会做 ELF 闭包 + 改名） |
| 客户机没网（`ip addr` 只有 `lo`） | 内核模块没加载（内核版本不匹配） | 见[升级 Alpine](#升级-alpine例如到-324x) |
| `apk add` 报 `DNS: transient error` | 客户机 DNS 不通 | `/init` 里已固定公共 DNS；检查宿主是否真的能出网 |
| 卡在 `starting` 很久 | 首次启动要解压 15MB，或 TCG 慢 | 看 `getStatus().uptimeMs` 与 `engineLog` |
