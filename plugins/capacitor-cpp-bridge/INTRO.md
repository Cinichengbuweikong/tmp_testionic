# capacitor-cpp-bridge 插件介绍

> 一句话：**给 Capacitor 插件装一条"能跑 C/C++"的通道——WebView 调 native，native 调同一份 C/C++ 核心代码。**

## 它解决什么问题

Capacitor 的插件默认只能用 Java/Kotlin 或 Swift 写。但有两类需求绕不开 C/C++：

- **复用已有 C/C++ 库**（图像处理、音视频编解码、加解密、算法库、游戏引擎、SQLite 之类的 C 库）；
- **性能敏感的计算**（避免在 JS 里做，也不想用 Java/Swift 重写一遍）。

难点不在"能不能调"，而在于：

1. **Android 和 iOS 的调用方式完全不同**（JNI vs. Swift/C 直调）；
2. **C/C++ 必须编译**，所以它天然是"模板"而不是成品插件；
3. **数据怎么过边界**（拷贝一次 100MB 就是几十毫秒）。

本插件把这三件事都做成了模板：一份共享核心 + 两个薄桥 + 构建配置 + 示例 + 文档。

## 它是什么（以及不是什么）

| 是 | 不是 |
| --- | --- |
| 一条已经打通的 **WebView → native → C/C++** 链路 | 某个具体功能（如"图片压缩"） |
| 一个可以往里加自己 C/C++ 代码的**骨架** | 开箱即用的成品插件 |
| 同时支持 **C++ 与纯 C**（两个编译单元都验证过） | 只能在 C++ 里用 |

## 架构

```
JS（WebView）
  │ Capacitor 桥（JSON，小数据）
  ▼
CppBridgePlugin.java / .swift        ← 本插件：参数校验、句柄管理
  │ JNI（Android） / 直接调用（iOS）
  ▼
shared/core（C++ + 纯 C）            ← 你的业务逻辑，两端共用同一份源码
```

关键设计：**核心只写一遍，桥是薄层**。C 接口用 `extern "C"`，所以 Swift 能直接 `import CppCore`，
JNI 也只是转发；`std::string`/模板这些 C++ 东西留在 `.cpp` 内部，不出现在边界上。

## 提供哪些示例

| API | 实现 | 作用 |
| --- | --- | --- |
| `getCoreInfo()` | C++ | 返回构建信息（platform/compiler/arch/cxx），确认 native 库已加载 |
| `add({a,b})` | **C++** | 两数相加，验证 C++ 调用链 |
| `cAdd({a,b})` | **纯 C** | 两数相加，验证 C 编译单元也在同一个库里 |
| `cStrlen({text})` | **纯 C** | 处理 `char*`，验证字符串边界 |
| `allocBuffer` / `fillBuffer` / `sumBuffer` / `freeBuffer` | C++ | ⚠️ **danger**：native 内存句柄，演示"数据不过桥"的零拷贝操作 |

## 30 秒上手

```ts
import { CppBridge } from 'capacitor-cpp-bridge';

const info = await CppBridge.getCoreInfo();
console.log(info.buildInfo);            // {"platform":"android","compiler":"clang 18.0.2",...}

console.log((await CppBridge.add({ a: 7, b: 35 })).result);   // 42（C++）
console.log((await CppBridge.cAdd({ a: 7, b: 35 })).result);  // 42（纯 C）

// ⚠️ danger：native 内存句柄，必须 free
const buf = await CppBridge.allocBuffer({ size: 4 * 1024 * 1024 });
await CppBridge.fillBuffer({ handle: buf.handle, value: 3 });
console.log((await CppBridge.sumBuffer({ handle: buf.handle })).sum); // 3145728
await CppBridge.freeBuffer({ handle: buf.handle });
console.log(await CppBridge.getBufferStats()); // { count: 0, bytes: 0 }
```

## 句柄 API 为什么被标成 danger

它操作的是 **native 内存**，JS 只拿到一个指针数值（`handle`）：

- 不 `free` 就**泄漏到进程退出**；
- 进程重启后 handle **失效**；
- 并发写同一 handle **没有加锁保护**；
- 只有 `getBufferStats()` 能事后发现"忘了 free"。

它是给"大块数据只在 native/C++ 之间流转"的场景准备的；**普通数据传递请用 base64/字符串，或 data-channel**。

## 平台要求

| 平台 | 要求 |
| --- | --- |
| Android | **NDK 27.1.12297006**（已固定）+ CMake 3.22.1；ABI：arm64-v8a / armeabi-v7a / x86_64 |
| iOS | Xcode 自带 clang；SwiftPM 自动编译 C++ target（本仓库用 GitHub Actions 验证） |

## 实测

| 项 | 结果 |
| --- | --- |
| 真机（Redmi K30S / Android 10，aarch64） | `loaded: true`；add/cAdd 都 42；4MB fill 6.7ms / sum 4.8ms |
| 模拟器（Pixel 6 / Android 16，x86_64） | 同上，fill 10.7ms / sum 9.3ms |
| C 与 C++ | 两个编译单元都在同一个 `libcppbridge.so` 里，实测都能调用 |
| 句柄生命周期 | alloc/fill/sum/free + 双重释放保护 + stats 归零 ✓ |
| autotest / smoke | 全部通过 |

## 已知边界

1. C/C++ 必须**编译**——改了核心代码要重新构建（Android 走 CMake，iOS 走 SPM）；
2. `c++_static`（默认）适合只有一个 native 库的 App；多个 C++ 插件共存时建议统一改 `c++_shared`；
3. 后台运行限制与所有原生代码一致（iOS 后台挂起、Android 需要前台服务）；
4. 大块数据不要走 Capacitor 桥（实测约 10–11 MB/s），用句柄或 data-channel。
