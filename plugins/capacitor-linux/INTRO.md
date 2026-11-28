# capacitor-linux（快速了解）

**在 App 里跑一个真的 Alpine Linux 虚拟机**：官方内核 + 官方 minirootfs + QEMU 软件模拟，
全部随 APK 离线分发，启动不联网。客户机是独立进程，**Linux 崩溃不连累 App**。

- Android：✅ 真机验证（Alpine 3.23.8 / kernel 6.18.65 / QEMU 11.1.3）
- iOS：⏳ 未实现（引擎已选定：iSH-ARM64，见 README 第 7 节）
- Web：❌ 明确返回不支持
- **最低版本：Android 7.0（API 24）+ arm64-v8a**

## 30 秒上手

```bash
# 1) 生成离线运行时（约 85MB 产物，不入库）
cd plugins/capacitor-linux && python3 scripts/fetch-linux-runtime.py

# 2) 宿主 App 必须允许解压原生库（否则 QEMU 无法 exec）
#    android/app/build.gradle:  android { packaging { jniLibs { useLegacyPackaging = true } } }

# 3) 正常构建
npm run build:plugins && npx cap sync android && npm run android:deploy
```

```ts
import { Linux } from 'capacitor-linux';

const caps = await Linux.getCapabilities();
if (!caps.supported) return console.log(caps.reasons);

await Linux.addListener('consoleOutput', (e) => term.write(e.data)); // 流式 stdout
await Linux.start({ rootfsMode: 'ram' });            // 内存 rootfs：真机 ~25 秒
// 或整盘落盘（apk add 的包也持久）：首次 ~61s，之后 ~40s，占 2GB
// await Linux.start({ rootfsMode: 'disk', diskSizeMb: 2048 });
await Linux.write({ data: 'apk add python3\n' });   // 像在终端里敲键盘
await Linux.sendKey({ key: 'ctrl+c' });             // 特殊键：中断前台程序
const r = await Linux.exec({ command: 'python3 -V' }); // stdout/stderr 分开返回

const share = await Linux.getShareDir();            // 宿主 <-> 客户机 /mnt/host（9p，双向）
await Linux.exec({ command: 'cp /mnt/host/a.txt /root/' });
```

## 它能做什么 / 不能做什么

| 能做 | 不能做 |
|---|---|
| 交互式 shell、跑 `apk`/`python3`/`node`/`vi` | 图形界面（没有 GPU/X11） |
| 流式 stdin/stdout/stderr，stdout 与 stderr 分开 | 从 App 外部连进客户机端口（只有 SLIRP 出网） |
| 特殊键（Ctrl-C/D/Z、方向键、F1~F12） | Ctrl-Z / `fg` / `bg`（为保住控制台关了 job control） |
| 宿主 ↔ 客户机共享目录（9p，实时双向） | 图形界面（没有 GPU/X11） |
| **整盘 rootfs 持久化**（`rootfsMode:'disk'`：`apk add` 的包重启后还在） | 在 32 位（armeabi-v7a）设备上跑 |
| Linux kernel panic 后 App 存活、可重新启动 | 与 QEMU 原生速度持平（TCG 慢 3~5 倍） |

## 为什么值得这么选

- **为什么不是 proot/chroot**：Android 10+ 的 SELinux 只允许 `execve` 标了 `apk_data_file`
  的文件（`nativeLibraryDir`），App 自己解压的 ELF 一律 `EACCES`。QEMU 不需要在 Android 上
  执行客户机二进制，绕开了整条限制，还顺带拿到真正的内核隔离。
- **为什么是 Alpine**：minirootfs 压缩后 4MB，`apk` 全功能，社区包成熟。
- **为什么两种串口**：tty 上 stdout/stderr 本来合并；第二条串口专门承载 stderr，
  这样 `exec()` 能给出**分离的**两条流。

## 体积（实测）

| 部分 | 大小 |
|---|---|
| QEMU 可执行文件（Termux bionic 构建） | 29.5 MB |
| QEMU 依赖库闭包（69 个，改名打包） | 37.8 MB |
| Alpine 内核（`linux-virt`） | 10.4 MB |
| 离线 rootfs（minirootfs + 13 个内核模块 + e2fsprogs） | 6.3 MB |
| **合计** | **≈ 85 MB**（APK 压缩后约 +60MB） |

细化说明、API 字段表、升级/换发行版步骤、踩坑清单都在 [README.md](README.md)。
