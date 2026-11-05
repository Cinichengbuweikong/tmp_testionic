# Capacitor C/C++ 插件模板 —— 调研与实现规划

> 状态：**调研阶段**（未开始实现）。本文档记录可行性、方案对比、关键坑与推荐架构，
> 供后续实现 `capacitor-cpp-bridge` 插件模板时参考。
>
> 目标：提供一个**能在 Android / iOS 上运行 C/C++ 代码**的 Capacitor 插件模板——
> 即"桥梁"。具体跑什么 C/C++ 代码，由后续开发者/在模板基础上补充源码并编译后决定。

---

## 1. 结论先行

**完全可行，且两端都有成熟路径**：

| 平台 | 方案 | 依赖 | 发布成本 |
| --- | --- | --- | --- |
| Android | **NDK + CMake**（`externalNativeBuild`）+ JNI 薄桥 | 用户需装 NDK（Android Studio 自带）；或预编译 `.so` | 中 |
| iOS | **SPM 两个 target**（纯 C/C++ target + Swift 桥 target，Swift target 依赖它） | Xcode 自带 clang | 低 |

关键点：
1. **一份 C/C++ 核心 + 两套薄桥**（JNI / Objective-C++），核心逻辑跨平台复用；
2. **零拷贝可行**：JNI 的 `NewDirectByteBuffer`、iOS 的 `Data(bytesNoCopy:)` / `UnsafeMutableRawPointer`；
3. 插件可以做成"**模板 + 一个最小可运行示例**"（示例 API：echo、字符串处理、二进制 hash），
   后续开发者把示例换成自己的 C/C++ 逻辑即可。

---

## 2. Android 侧方案

### 2.1 插件里怎么引入 C/C++

`android/build.gradle` 增加：

```gradle
android {
    namespace = "com.testionic.cppbridge"
    ndkVersion = "27.0.12077973"          // 固定 NDK 版本，避免各人环境不一致
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags "-std=c++17"
                arguments "-DANDROID_STL=c++_shared"   // 或 c++_static
            }
        }
        ndk { abiFilters "arm64-v8a", "armeabi-v7a", "x86_64" }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
}
```

`src/main/cpp/CMakeLists.txt`：

```cmake
cmake_minimum_required(VERSION 3.22)
project(cppbridge LANGUAGES C CXX)
add_library(cppbridge SHARED
    bridge.cpp                 # JNI 薄桥（只在 Android 编译）
    core/cpp_core.cpp          # 纯 C++ 核心（跨平台，iOS 也用）
)
target_include_directories(cppbridge PRIVATE core)
target_compile_options(cppbridge PRIVATE -fexceptions -frtti)
```

> **发布形态二选一**：
> - **A. 源码 + CMake**（推荐给模板）：使用方必须有 NDK。Capacitor 用户多数装过 Android Studio，NDK 可自动下载。
> - **B. 预编译 `.so`**：把各 ABI 的 `.so` 放进 `src/main/jniLibs/<abi>/`，使用方无需 NDK，
>   但仓库体积增大（每个 ABI 几百 KB~几 MB），且 ABI 覆盖必须完整（arm64-v8a 必须）。

### 2.2 JNI 桥（薄层）

```java
// CppBridgePlugin.java
private static boolean loaded = false;
private static synchronized void ensureLoaded() {
    if (!loaded) {
        System.loadLibrary("cppbridge");
        loaded = true;
    }
}
private native String nativeEcho(String input);
private native byte[] nativeHash(byte[] input);
```

```cpp
// bridge.cpp
#include <jni.h>
#include "cpp_core.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_testionic_cppbridge_CppBridgePlugin_nativeEcho(JNIEnv* env, jobject, jstring input) {
    const char* chars = env->GetStringUTFChars(input, nullptr);
    std::string result = cpp_core::echo(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(input, chars);
    return env->NewStringUTF(result.c_str());
}
```

**注意事项**：
- `extern "C"` + `JNIEXPORT` 必须；函数名与 `Java_<包名>_<类名>_<方法名>` 严格对应（或 `RegisterNatives` 动态注册）。
- JNI 函数运行在 Java 调用线程；**C++ 回调 Java 时必须 `AttachCurrentThread`**（用完 `DetachCurrentThread`），
  不能跨线程直接用 `JNIEnv*`（`JNIEnv*` 是线程私有的）。
- **LocalRef 泄漏**：循环里大量 `NewString`/`NewObject` 要配 `DeleteLocalRef`，否则 JNI 局部引用表溢出（默认 512 个）。
- **异常**：C++ 异常不能穿过 JNI 边界，必须 `try/catch` 转成 `env->ThrowNew`。
- C++ 侧分配的内存**谁分配谁释放**（用 `std::unique_ptr`/`std::vector` 自动管理，或显式 `free`）。

### 2.3 零拷贝（关键）

Java ↔ C++ 共享大 buffer 的**零拷贝**方式：

```java
// Java 侧：堆外 DirectByteBuffer（由 C++ 分配并包装）
ByteBuffer buf = nativeAllocateBuffer(10 * 1024 * 1024);  // C++: NewDirectByteBuffer(malloc(len), len)
// 直接读写 buf（C++ 代码看到的同一块内存）
```

```cpp
// C++ 侧
extern "C" JNIEXPORT jobject JNICALL
Java_..._nativeAllocateBuffer(JNIEnv* env, jobject, jint size) {
    void* data = malloc(size);                     // 或用 NEW 缓存池
    return env->NewDirectByteBuffer(data, size);   // 包装，不拷贝
}
extern "C" JNIEXPORT void JNICALL
Java_..._nativeFreeBuffer(JNIEnv* env, jobject, jobject directBuffer) {
    void* data = env->GetDirectBufferAddress(directBuffer);
    free(data);
}
```

- 反向（Java 数组 → C++）：`GetByteArrayElements` 可能**复制**（`isCopy` 标志），大数据用 DirectByteBuffer 更好。
- **不要**在 JNI 边界反复拷贝大数组——一次拷贝 100MB 约 20~50ms，会毁掉性能。

---

## 3. iOS 侧方案

### 3.1 SPM 混合语言限制（最重要的一点）

**SwiftPM 的单个 target 不能同时包含 Swift 和 C/C++ 源码**。必须拆两个 target：

```
plugins/capacitor-cpp-bridge/
├─ Package.swift
├─ Sources/
│  ├─ CppCore/                 # 纯 C/C++ target
│  │  ├─ include/cpp_core.h
│  │  └─ cpp_core.cpp
│  └─ CppBridgePlugin/         # Swift target（依赖 CppCore）
│     └─ CppBridgePlugin.swift
```

```swift
// swift-tools-version: 5.9
let package = Package(
    name: "CapacitorCppBridge",
    platforms: [.iOS(.v15)],
    products: [.library(name: "CapacitorCppBridge", targets: ["CppBridgePlugin"])],
    dependencies: [.package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")],
    targets: [
        .target(                                          // 纯 C++ target
            name: "CppCore",
            path: "Sources/CppCore",
            publicHeadersPath: "include",
            cxxSettings: [.unsafeFlags(["-std=c++17"])]
        ),
        .target(                                          // Swift 桥 target
            name: "CppBridgePlugin",
            dependencies: [
                "CppCore",
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "Sources/CppBridgePlugin"
        )
    ]
)
```

- Swift target 里 `import CppCore` 后直接调用 C 函数（头文件里用 `extern "C"` / `CPP_EXTERN_C_BEGIN`）。
- 如果 C++ API 复杂，建议**在 C++ target 里导出纯 C 风格接口**（`cpp_core_echo(const char*)`、不透明句柄），
  Swift 只和 C 接口打交道，避免 C++ 模板/STL 类型暴露（Swift 无法直接用 `std::string`）。

### 3.2 零拷贝（iOS）

```swift
// Swift → C++：传指针，不拷贝
data.withUnsafeMutableBytes { raw in
    cpp_process(raw.baseAddress, raw.count)
}

// C++ → Swift：用 malloc 的内存包装成 Data，不拷贝
let ptr = cpp_alloc(1_000_000)                      // void*
let data = Data(bytesNoCopy: ptr!, count: 1_000_000, deallocator: .free)  // 零拷贝
```

- `Data(bytesNoCopy:deallocator:)` 是最常用的零拷贝桥；
- 也可以 `UnsafeMutableRawPointer` + `Data.init(bytesNoCopy:)`；
- **内存所有权**：用 `Data(deallocator: .free)` 时必须是 `malloc` 分配的内存。
- C++ 回调 Swift：用 `std::function` 回调 C 函数指针（`@convention(c)`）+ `Unmanaged.passUnretained` 传 context。

### 3.3 另一个选择：预编译 `binaryTarget`

如果 C++ 代码用 CMake 构建成静态库 `.a` 或 XCFramework，SPM 可以用 `binaryTarget(name:path:)` 引入。
优点是构建快、无需把源码给使用方；缺点是必须为每个架构预编译（模拟器 x86_64/arm64 + 真机 arm64），
且 CI 需要 macOS 构建产物。**模板阶段建议用源码 target**，简单透明。

---

## 4. 跨平台核心设计（推荐）

```
┌───────────────── 共享 C++ 核心（纯逻辑，无平台依赖）─────────────────┐
│  core/cpp_core.h        // extern "C" 纯 C 接口（Swift 易用）        │
│  core/cpp_core.cpp      // 实现（可自由使用 STL/模板）                │
└───────────────┬───────────────────────────────┬─────────────────────┘
                │ JNI（Android）                 │ C 接口（iOS）
        ┌───────▼────────┐              ┌───────▼────────┐
        │ bridge.cpp     │              │ Swift target    │
        │ DataChannelPlugin 中用        │ (CppBridgePlugin)│
        └───────┬────────┘              └───────┬────────┘
        ┌───────▼────────┐              ┌───────▼────────┐
        │ Capacitor      │              │ Capacitor      │
        │ (Java)         │              │ (Swift)        │
        └────────────────┘              └────────────────┘
```

**核心只写一遍，两端桥都是薄层**。建议核心导出 C 接口（`extern "C"`）：
- Swift 能直接调用（无需 Objective-C++）；
- Android 的 JNI 桥也简单；
- 避免 C++ 类型在边界上传播（ABI/命名修饰问题）。

---

## 5. 模板应该包含什么

`capacitor-cpp-bridge`（模板）建议提供：

1. **两个最小示例**（证明链路通）：
   - `echo({ text })` → 字符串往返（验证 JNI/Swift 桥）；
   - `hash({ base64 })` → 计算 SHA-256 并返回 hex（验证二进制 + 内存边界）。
2. **零拷贝示例**（模板的核心价值）：
   - `allocateBuffer({ size })` → 返回 DirectByteBuffer（Android）/ `Data`（iOS）的**抽象**
     （JS 侧无法直接拿 native 指针，所以示例改为：`processFile({ path })` 在 native 侧流式处理文件，
     展示"C++ 处理数据但不经过 JS"）。
3. **一份文档**：如何在模板基础上加自己的 C++ 源码（CMake 改哪里、SPM 加文件、如何重新编译）。
4. **验证脚本**：`npm run verify:android`（真机/模拟器）、iOS CI（GitHub Actions macOS）。

**API 设计（模板示例）**：

```ts
export interface CppBridgePlugin {
  /** 返回 C++ 核心的版本/构建信息（验证 .so / framework 已加载） */
  getCoreInfo(): Promise<{ version: string; buildTime: string; platform: 'android' | 'ios' }>;
  /** echo：字符串往返（验证桥） */
  echo(options: { text: string }): Promise<{ text: string; roundTripMs: number }>;
  /** 计算 SHA-256（验证二进制输入输出） */
  sha256(options: { base64: string }): Promise<{ hex: string }>;
  /** 示例：用 C++ 处理文件（大文件不经过 JS，展示零拷贝/流式思路） */
  processFile(options: { path: string; outputPath: string }): Promise<{ bytesIn: number; bytesOut: number; elapsedMs: number }>;
}
```

---

## 6. 构建与 CI

| 平台 | 需求 | GitHub Actions |
| --- | --- | --- |
| Android | NDK（版本固定）、CMake | `android-actions/setup-android` + `sdkmanager "ndk;27.0.12077973"` |
| iOS | Xcode（自带 clang） | 现有 macOS workflow 即可（SPM 自动编译 C++ target） |

**注意**：
- NDK 版本必须在 `build.gradle` 固定（`ndkVersion`），否则不同机器 NDK 版本不一致会引入 ABI 问题；
- `abiFilters` 建议 `arm64-v8a`（必需）+ `armeabi-v7a`（可选）+ `x86_64`（模拟器用）；
- iOS 模拟器需要 `x86_64`（Intel Mac）或 `arm64`（Apple Silicon）——SPM 会自动按架构编译 ✓；
- C++ 标准建议 `-std=c++17`（两端都支持良好）。

---

## 7. 关键坑清单（实现时对照）

| # | 坑 | 说明 |
| --- | --- | --- |
| 1 | SwiftPM 不能混合语言 | 必须拆 CppCore（纯 C/C++）+ Swift target |
| 2 | JNI 函数名 | `Java_包名_类名_方法名`，包名里的 `.` 换成 `_`；或用 `RegisterNatives` |
| 3 | JNIEnv 线程私有 | C++ 回调 Java 必须 `AttachCurrentThread` |
| 4 | LocalRef 泄漏 | 循环内创建引用要 `DeleteLocalRef`（512 上限） |
| 5 | C++ 异常不能跨 JNI | try/catch 转 `ThrowNew` |
| 6 | 大数组拷贝 | 用 DirectByteBuffer / `Data(bytesNoCopy:)`，不要 `GetByteArrayElements`/`Array→Data` |
| 7 | 内存所有权 | 谁 malloc 谁 free；Swift 用 `Data(deallocator: .free)` 时内存必须是 malloc 的 |
| 8 | STL 跨边界 | 不要让 `std::string`/`std::vector` 出现在 C 接口上（Swift 无法直接使用） |
| 9 | NDK 版本 | `ndkVersion` 固定，CI 安装同版本 |
| 10 | 符号导出 | `extern "C"` + `-fvisibility=hidden` 时记得 `__attribute__((visibility("default")))` |
| 11 | `c++_shared` vs `c++_static` | 多个 .so 共用时用 shared；单 so 可用 static（体积更小） |
| 12 | iOS 真机 vs 模拟器架构 | 预编译方案必须覆盖 arm64（真机）+ x86_64/arm64（模拟器） |
| 13 | bitcode | Xcode 15+ 已废弃，不需要 |
| 14 | 后台运行 | 与所有原生代码一样：iOS 后台会被挂起；Android 需要前台服务 |

---

## 8. 与 data-channel / native-files 的协作

这是这套插件组合最有价值的场景：

```
[系统文件选择器] → native-files 返回 uri
        ↓ copyToAppStorage
[App 自有目录 /dataChannel] → data-channel HTTP server
        ↓ 如果要在 C++ 里处理（如视频转码）
[C++ 核心] ← DirectByteBuffer / Data(bytesNoCopy:) 零拷贝
        ↓ 处理结果
[data-channel WebSocket] → 实时推给 WebView 预览
```

链路已经打通：`native-files`（选择）→ `data-channel`（高速传输）→ `cpp-bridge`（原生处理）→ `data-channel`（回传）。

---

## 9. 推荐实现顺序（供后续）

1. 建插件骨架（`capacitor-cpp-bridge`）：两端最小 echo + SHA-256；
2. 打通 Android NDK 构建（`externalNativeBuild`），真机验证 `getCoreInfo/echo/hash`；
3. 打通 iOS SPM 双 target，CI 验证；
4. 加零拷贝示例（文件流式处理）；
5. 写"如何添加你自己的 C++ 代码"文档；
6. 双端冒烟 + iOS CI 全绿。

---

## 10. 参考

- Android NDK + CMake：<https://developer.android.com/ndk/guides/cmake>
- JNI 规范（DirectByteBuffer / 线程 / 引用）：<https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/jniTOC.html>
- SwiftPM C++ 支持：<https://github.com/apple/swift-package-manager/blob/main/Documentation/Usage.md>
- `Data(bytesNoCopy:)`：<https://developer.apple.com/documentation/foundation/data/init(bytesnocopy:count:deallocator:)>
- Capacitor 自定义插件：<https://capacitorjs.com/docs/plugins>
