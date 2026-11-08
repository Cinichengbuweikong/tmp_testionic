# capacitor-cpp-bridge

**C/C++ 桥模板**：让 Capacitor 插件在 Android / iOS 上调用**同一份 C/C++ 核心代码**，
并提供一条「WebView → native → C/C++」的最小可运行链路。

> 这不是一个"功能插件"，而是**模板**：
> 它只负责把桥搭好（JNI / Swift 薄桥、构建配置、句柄管理、示例），
> 真正的业务 C/C++ 代码由后续开发者补在 `shared/core/` 里，重新编译后才是"最终插件"。

## 它提供什么

| 层 | 内容 |
| --- | --- |
| 共享核心 | `shared/core/`：一个 **C++ 编译单元**（`cpp_core.cpp`）+ 一个 **纯 C 编译单元**（`c_core.c`），同一份源码被 Android/iOS 编译 |
| C 接口 | `shared/core/include/cppcore.h`：`extern "C"`，Swift 可直接 import，JNI 可直接调 |
| Android 桥 | `android/src/main/cpp/bridge.cpp`（JNI 薄桥，只做转发 + 异常转换） |
| iOS 桥 | `ios/Sources/CppBridgePlugin/CppBridgePlugin.swift`（`import CppCore` 直接调 C 函数） |
| JS API | `getCoreInfo` / `add`（C++）/ `cAdd`（C）/ `cStrlen`（C）/ **句柄 API**（native 内存，⚠️ danger） |

## 分层与数据流

```
JS（WebView）
  │  Capacitor 桥（JSON；大块数据不要走这里）
  ▼
CppBridgePlugin.java / CppBridgePlugin.swift     ← 参数校验、句柄管理（本插件）
  │  JNI（nativeAdd / nativeBufferFill…）       ← 只做转发和类型转换
  │  Swift 直接调用（cppcore_add()…）
  ▼
shared/core（C++ / C）                            ← 你的业务逻辑写这里，两端共用
```

## 环境要求

| 平台 | 要求 |
| --- | --- |
| Android | **NDK**（本模板固定 `ndkVersion = 27.1.12297006`）+ CMake 3.22.1；`abiFilters` = `arm64-v8a` / `armeabi-v7a` / `x86_64` |
| iOS | Xcode（自带 clang）；SwiftPM 自动按架构编译 C++ target |

> Android 侧用 `externalNativeBuild` 在**插件模块内**编译，App 构建时会自动把
> `libcppbridge.so` 打进 APK（3 个 ABI，debug 未 strip 时约 500–700KB/个）。

## 构建体系：Android 用 CMake，iOS 用 SPM

两端用的是**完全不同的构建系统**，所以目录结构不对称——这不是漏了文件。

| | Android | iOS |
| --- | --- | --- |
| 构建系统 | Gradle → **NDK + CMake**（`externalNativeBuild`） | Xcode → **SwiftPM** |
| 构建脚本 | `android/build.gradle` + `android/src/main/cpp/CMakeLists.txt` | 只有 **`Package.swift`** |
| C/C++ 源文件如何被发现 | **显式写进** `add_library(...)` | **自动**：target `path` 目录下的 `.c/.cpp` 全部参与编译 |
| 头文件搜索路径 | `target_include_directories(...)` | `publicHeadersPath: "include"` |
| 语言标准 | `set_target_properties(... CXX_STANDARD 17)` | 包级 `cxxLanguageStandard: .cxx17` |
| 编译产物 | 每个 ABI 一个 `libcppbridge.so`（运行时 `System.loadLibrary` 加载） | **没有独立 .so**：C/C++ 被静态链接进 App 主二进制 |

iOS 侧的 `Package.swift` 就扮演了 CMakeLists 的角色：

```swift
.target(
    name: "CppCore",
    path: "shared/core",              // 这个目录下的 .c/.cpp 自动全部参与编译
    publicHeadersPath: "include",     // 相当于 include 目录
    cSettings: [.headerSearchPath("include")],
    cxxSettings: [.headerSearchPath("include")]
),
// ...
cxxLanguageStandard: .cxx17           // 相当于 -std=c++17
```

**由此带来的不对称**（改代码时要注意）：

| 在 `shared/core/` 里新增文件 | 需要改什么 |
| --- | --- |
| Android | **要改** `CMakeLists.txt`，把新文件加进 `add_library(cppbridge SHARED ...)` |
| iOS | **不用改**（SPM 自动发现） |

> 如果嫌 Android 那边麻烦，可以把 `add_library` 改成自动收集：
>
> ```cmake
> file(GLOB CPP_SOURCES CONFIGURE_DEPENDS
>     ${SHARED_DIR}/*.cpp
>     ${SHARED_DIR}/*.c
> )
> add_library(cppbridge SHARED bridge.cpp ${CPP_SOURCES})
> ```
>
> `CONFIGURE_DEPENDS` 会在文件增删时自动重新配置。模板默认用**显式列表**，因为它更醒目地提醒
> "加了文件要同步改这里"，也避免不同 CMake 版本的行为差异。

**iOS 能不能也用 CMake？** 技术上可以（CMake 有 iOS toolchain，能编 `.a`/`.xcframework`），
但那样通常要走 `CMake → XCFramework → SPM binaryTarget` 的流程。模板阶段用**源码 target** 更简单、
CI 更好排错、改代码即时生效；预编译只在"使用方不想给源码 / 构建太慢"时才值得。

## 「模板只有一个 .so」是什么意思

指的是：**这个插件把所有 C/C++ 源码编译链接成一个 native 库**——不是"整个 APK 只有一个 .so"。

```cmake
add_library(cppbridge SHARED
    bridge.cpp                   # JNI 桥
    ${SHARED_DIR}/cpp_core.cpp   # C++ 编译单元
    ${SHARED_DIR}/c_core.c       # 纯 C 编译单元（自动用 C 编译器）
)
```

- 三个源文件 → 链接成**一个** `libcppbridge.so`；Java 侧 `System.loadLibrary("cppbridge")` 加载它，
  C++ 的 `cppcore_add` 和纯 C 的 `cppcore_c_add` 都在里面；
- `-DANDROID_STL=c++_static` 让 **C++ 运行时（libc++）也静态进这个 .so**，所以不需要额外的 `libc++_shared.so`。

**但"每个 ABI 各一份"**，所以 APK 里实际有 3 个文件（本仓库构建产物实测）：

```
lib/arm64-v8a/libcppbridge.so      685,048 bytes
lib/armeabi-v7a/libcppbridge.so    500,304 bytes
lib/x86_64/libcppbridge.so         615,952 bytes
```

设备运行时只加载**匹配自己 ABI 的那一份**（真机 arm64 加载第一份，模拟器 x86_64 加载第三份）。

| 说法 | 是否准确 |
| --- | --- |
| "插件只有一个 native 库" | ✅ 逻辑上是一个库 |
| "APK 里只有一个 .so" | ❌ 几个 ABI 就有几份，另外还有其它依赖的 .so |
| "C 和 C++ 混在一个库里" | ✅ `.c` 用 C 编译器、`.cpp` 用 C++ 编译器，最后链接到一起 |

> 想拆成多个库时：在 CMake 里再加 `add_library(foo SHARED ...)`，然后 `System.loadLibrary("foo")`
> （被依赖的先加载）。模板故意只做一个，省去加载顺序的心智负担。

## JS API

```ts
import { CppBridge } from 'capacitor-cpp-bridge';

// 1) 确认 native 库加载成功 + 看构建信息
const info = await CppBridge.getCoreInfo();
// { platform: 'android', loaded: true, library: 'libcppbridge.so',
//   buildInfo: '{"platform":"android","compiler":"clang 18.0.2","arch":"aarch64","cxx":"C++17"}' }

// 2) C++ 函数 / 纯 C 函数
await CppBridge.add({ a: 7, b: 35 });   // { result: 42, impl: 'cpp' }
await CppBridge.cAdd({ a: 7, b: 35 });  // { result: 42, impl: 'c' }
await CppBridge.cStrlen({ text: 'hello 中文' }); // { length: 12 }（UTF-8 字节数）
```

### ⚠️ DANGER：句柄 API（native 内存，零拷贝演示）

```ts
const buf = await CppBridge.allocBuffer({ size: 4 * 1024 * 1024 }); // size 必须是 4 的倍数
await CppBridge.fillBuffer({ handle: buf.handle, value: 3 });        // 纯 native 操作，数据不过桥
const { sum } = await CppBridge.sumBuffer({ handle: buf.handle });   // 4MB/4×3 = 3145728
await CppBridge.freeBuffer({ handle: buf.handle });                  // 必须调用！
```

**规则（务必遵守）**：

1. **必须 `freeBuffer`**，否则这块 native 内存会泄漏到进程退出；
2. `handle` 是 native 指针数值：**进程重启后失效**，不要持久化；
3. 不要并发对同一 handle 写（模板没有加锁）；
4. 重复 `freeBuffer` 会返回 `{ freed: false, reason: 'UNKNOWN_OR_ALREADY_FREED' }`（不会崩溃）；
5. `getBufferStats()` 返回当前未释放的 `count`/`bytes`——**用它检查有没有忘记 free**。

## 如何添加 / 删除 C/C++ 调用（模板的核心用法）

一个 JS 能调用的 C/C++ 函数，需要穿过 **5 层**。所以新增/删除都是"按层改一遍"。

| 层 | 文件 | 作用 |
| --- | --- | --- |
| ① 共享核心声明 | `shared/core/include/cppcore.h` | C 接口（`extern "C"`），Swift/JNI 都看它 |
| ② 共享核心实现 | `shared/core/*.cpp` 或 `*.c` | 真正的逻辑（两端共用同一份） |
| ③ Android 桥 | `android/src/main/cpp/bridge.cpp` + `CppBridgePlugin.java` | JNI 函数 + Java `native` 声明 + `@PluginMethod` |
| ④ iOS 桥 | `ios/Sources/CppBridgePlugin/CppBridgePlugin.swift` | `@objc` 方法 + `pluginMethods` 注册 |
| ⑤ JS 类型 | `src/definitions.ts` | 前端看到的类型（可选：测试页 + autotest） |

### A. 新增一个调用（以 `multiply` 乘法为例）

**① 声明** — `shared/core/include/cppcore.h`

```c
int32_t cppcore_multiply(int32_t a, int32_t b);
```

**② 实现** — `shared/core/cpp_core.cpp`（或新建 `xxx.cpp` / `xxx.c`）

```c
int32_t cppcore_multiply(int32_t a, int32_t b) { return a * b; }
```

> **新建了文件的话**：Android 还要在 `android/src/main/cpp/CMakeLists.txt` 的
> `add_library(cppbridge SHARED ...)` 里加上这个文件；iOS 不用改（SPM 自动收集）。
> 忘了改 CMake 的症状是：链接期 `undefined reference to cppcore_multiply`。

**③ Android 桥**

```cpp
// android/src/main/cpp/bridge.cpp
JNIEXPORT jint JNICALL
Java_com_testionic_cppbridge_CppBridgePlugin_nativeMultiply(JNIEnv*, jobject, jint a, jint b) {
    return cppcore_multiply(a, b);
}
```

```java
// android/src/main/java/com/testionic/cppbridge/CppBridgePlugin.java
private native int nativeMultiply(int a, int b);

@PluginMethod
public void multiply(PluginCall call) {
    JSObject ret = new JSObject();
    ret.put("result", nativeMultiply(call.getInt("a", 0), call.getInt("b", 0)));
    call.resolve(ret);
}
```

> 句柄/大整数参数不要用 `getLong()`（它只认 `Long`，JS 小整数是 `Integer`）——
> 用本文件已有的 `readLong(call, key)`。

**④ iOS 桥** — `CppBridgePlugin.swift`

```swift
@objc func multiply(_ call: CAPPluginCall) {
    let a = Int32(truncatingIfNeeded: readInt64(call, "a") ?? 0)
    let b = Int32(truncatingIfNeeded: readInt64(call, "b") ?? 0)
    call.resolve(["result": Int(cppcore_multiply(a, b))])
}
```

并把一行加进 `pluginMethods`：

```swift
CAPPluginMethod(name: "multiply", returnType: CAPPluginReturnPromise),
```

> 漏了这行 → JS 调用时报 `"multiply" is not implemented on iOS`（Android 却正常）。

**⑤ JS 类型** — `src/definitions.ts`

```ts
multiply(options: { a: number; b: number }): Promise<{ result: number }>;
```

（可选）在 `src/views/PluginTestView.vue` 加个按钮、在 autotest 里加断言，方便回归。

**⑥ 重新构建**

```bash
npm run build                    # 插件 TS → dist/esm
npx cap sync android             # 只在 Java/类型有变化时需要
npm run android:deploy -- -Device <id>   # 会跑 CMake 编译 .so
# iOS：本机 Xcode 或 CI（SPM 自动编译 C/C++）
```

### B. 删除一个调用（反着走一遍）

1. **`src/definitions.ts`**：删掉接口方法（前端会立刻编译报错，这是想要的）；
2. **`CppBridgePlugin.swift`**：删掉 `@objc func xxx` **和** `pluginMethods` 里那行；
3. **`CppBridgePlugin.java`**：删掉 `@PluginMethod` 方法 **和** `private native ...` 声明；
4. **`bridge.cpp`**：删掉对应的 `Java_..._nativeXxx` 函数；
5. **`cppcore.h` / `cpp_core.cpp`**：删掉声明与实现（若这个文件整个不要了，**别忘了 CMake 里那行**）；
6. **重新构建**（同 ⑥）。

> 只删 JS 类型不删 native 方法不会报错，但会留下死代码；反过来删了 native 却留 JS 类型，
> 运行时会报 `"xxx" is not implemented`。**删的顺序建议：TS → Swift → Java/JNI → C/C++。**

### C. 改漏了会怎样（症状对照）

| 漏改的地方 | 症状 |
| --- | --- |
| `cppcore.h` 没声明 | Swift/`bridge.cpp` 编译报 "no member named …" |
| 新文件没加进 CMakeLists | 链接失败 `undefined reference to …` |
| `bridge.cpp` 没实现 JNI | `UnsatisfiedLinkError: No implementation found for native …` |
| Swift 没加 `pluginMethods` | iOS 上 `"xxx" is not implemented` |
| Java 没加 `private native` 声明 | Java 编译报错（找不到方法） |
| 忘记 `npx cap sync` | App 里仍是旧插件代码/旧类型 |

### D. 几个容易踩的细节

1. **字符串 / 二进制边界**：C 接口只传 `char*` / `uint8_t*` + 长度，
   **不要**把 `std::string` / `std::vector` / 模板放到 `cppcore.h` 里（Swift 用不了，ABI 也不稳定）；
2. **大块数据别过 JS 桥**：用句柄（native 内存）或 `Data(bytesNoCopy:)` / `DirectByteBuffer`；
   走 base64 桥实测只有约 10–11 MB/s（见 native-files README 的性能一节）；
3. **异常不能跨 JNI**：C++ 异常穿过 JNI 边界是未定义行为，必须在 `bridge.cpp` 里
   `try/catch` 转成 Java 异常（模板已有 `throwIllegalArgument`）；
4. **线程**：`JNIEnv*` 是线程私有的；C++ 在别的线程回调 Java 前必须 `AttachCurrentThread`
   （用完 `DetachCurrentThread`），不能跨线程复用 `JNIEnv*`；
5. **内存所有权**：`cppcore_buffer_alloc` 分配的内存只能 `cppcore_buffer_free` 释放
   （内部是 `malloc`/`free`，**不要**用 `delete`）；Swift 用 `Data(bytesNoCopy:deallocator: .free)` 时同理；
6. **纯 C 文件只能用 C 语法**（`NULL` 而不是 `nullptr`，不能有 `class`/`namespace`/`template`），
   但可以 `#include "cppcore.h"`——头文件里的 `extern "C"` 在 C 编译器下会被忽略；
7. **JNI LocalRef**：循环里创建引用（`NewString` 等）要配 `DeleteLocalRef`，
   否则局部引用表（默认 512）会溢出。

## 零拷贝与互操作

| 方向 | Android | iOS |
| --- | --- | --- |
| native → C++ 传指针 | `GetDirectBufferAddress(ByteBuffer)`（**不拷贝**） | `withUnsafeMutableBytes { cpp_process($0.baseAddress, $0.count) }`（**不拷贝**） |
| C++ 分配 → native 持有 | `env->NewDirectByteBuffer(ptr, len)` → `ByteBuffer`（**不拷贝**） | `Data(bytesNoCopy: ptr, count: len, deallocator: .free)`（**不拷贝**） |
| 调用开销 | JNI 一次约百纳秒~微秒级 | Swift 直接调 C 函数，基本等于本地调用 |

**结论**：C++ ↔ native 的互操作几乎无损耗；真正的瓶颈是 **JS ↔ native**（base64/JSON，实测约 10–11 MB/s）。
所以模板的句柄 API 把"大块数据"留在 native 侧，只让 JS 传一个 handle。

## 实测（真机 Redmi K30S / Android 10 + 模拟器 Pixel 6 / Android 16）

| 项 | 结果 |
| --- | --- |
| `getCoreInfo` | `loaded: true`，`buildInfo` 报 `clang 18.0.2 / aarch64 / C++17`（模拟器 `x86_64`） |
| `add`（C++）/ `cAdd`（纯 C） | 都返回 42 ✓（**证明 C 与 C++ 都能跑**） |
| `cStrlen('hello 中文')` | 12（UTF-8 字节数）✓ |
| 4MB 内存块 | fill 6.7ms / sum 4.8ms（真机），模拟器 10.7/9.3ms；`sum = 3145728` ✓ |
| 句柄生命周期 | alloc/fill/sum/free + 双重释放保护 + stats 归零 ✓ |
| autotest / smoke | 真机 + 模拟器 `ok:true` / **PASS** |

## 构建相关：`c++_static` vs `c++_shared`

- `c++_shared`：把 `libc++_shared.so` 作为独立 .so 打进去，多个 native 库**共用**一份 C++ 运行时；
- `c++_static`：把 C++ 运行时**静态链进** `libcppbridge.so`（本模板默认）。

本插件只有一个 .so，用 **`c++_static`** 更省事（APK 里少一个 .so，也没有运行时依赖）。
如果你以后在一个 App 里放了**多个**都用到 C++ 的插件，建议统一改成 `c++_shared`，否则符号/运行时可能重复。

## 已知坑

| # | 坑 | 处理 |
| --- | --- | --- |
| 1 | SwiftPM 不能把 Swift 和 C/C++ 放进同一个 target | 已拆成 `CppCore` + `CppBridgePlugin` 两个 target |
| 2 | JNI 函数名必须与 `Java_<包名>_<类名>_<方法名>` 严格对应 | 改包名/类名时别忘了改 `bridge.cpp`（或改用 `RegisterNatives`） |
| 3 | `JNIEnv*` 是线程私有的 | C++ 回调 Java 前必须 `AttachCurrentThread` |
| 4 | LocalRef 泄漏（默认上限 512） | 循环里创建引用要 `DeleteLocalRef` |
| 5 | C++ 异常不能跨 JNI | 已在 `bridge.cpp` 里转成 `IllegalArgumentException` |
| 6 | 大数组跨 JNI 会被拷贝 | 用 DirectByteBuffer / `Data(bytesNoCopy:)` |
| 7 | 内存所有权 | `cppcore_buffer_alloc` 的内存只能 `cppcore_buffer_free`（内部 malloc/free） |
| 8 | STL 不能出现在 C 接口上 | 只在 .cpp 内部用 `std::string`/`std::vector` |
| 9 | NDK 版本不一致 | `ndkVersion` 已固定，换机器时保持一致 |
| 10 | iOS 真机 vs 模拟器架构 | 源码 target 由 SPM 自动按架构编译，无需关心 |

## 改完代码要重新编译吗（开发工作流）

**装插件是一次性的；改 C/C++ 必须重新编译 native。** 这是编译型语言的本质，
`npm run build`（TS）和 Vite 热更新都**不会**碰 C/C++。

| 你改的东西 | 要重编译 native 吗 | 怎么跑 |
| --- | --- | --- |
| **C/C++**（`shared/core/*.cpp` / `*.c`） | ✅ **必须**（CMake/NDK 重编 + 重新打包 APK） | `npm run android:deploy -- -Device <id>` |
| **Java 插件 / `bridge.cpp`**（JNI） | ✅ 必须（JNI 也在 native 库里） | 同上 |
| **Swift**（`CppBridgePlugin.swift`） | ✅ 必须 | 无 Mac → 推 CI；有 Mac → Xcode build |
| **TS 类型 / 测试页** | ❌ 不需要（只重打 web 资源） | `npm run android:deploy -- -Device <id>` |
| 只改 README / 文档 | ❌ | 不用 |

> 本项目没有配置 live-reload server，网页资源是打包进 APK 的，所以改页面也走 `android:deploy`。
> 如果以后配了 `server.url` 热更新，改 JS 就不用重装 App 了——但**改 C++ 仍然要**。

**实测重建耗时（本机）**：

| 场景 | 耗时 |
| --- | --- |
| 首次构建（3 个 ABI 全新编译） | 约 1–2 分钟 |
| **改一行 C++ 后重新出 APK（增量）** | **约 48 秒**（`gradlew assembleDebug` 实测 47.8s） |
| 只改 TS/页面（不动 native） | 约 20–30 秒（Gradle 缓存命中，不跑 CMake） |
| iOS（无 Mac，走 CI） | 一次约 10 分钟（含模拟器 autotest） |

增量编译快，是因为 CMake/ninja 只重编**改动的那几个文件**再链接。

### 想再快一点：开发期只编目标 ABI

把 `android/build.gradle` 里的

```gradle
abiFilters "arm64-v8a", "armeabi-v7a", "x86_64"
```

临时改成只留当前设备那一个（真机 arm64 用 `arm64-v8a`，模拟器用 `x86_64`），编译时间能再省 1/3~1/2。
**提交前记得改回来**，否则其它架构的设备装不上。

### 只改了 C/C++ 时，可以跳过哪些步骤

```bash
cd android && ./gradlew assembleDebug
adb -s <id> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <id> shell am start -n com.myapp.testionic/.MainActivity
```

跳过 `build:plugins`（TS 未变）和 `vite build`（页面未变）。不过 `android:deploy` 里这两步也就几秒，
图省事直接用脚本也行。

### 什么时候需要 `npx cap sync`

| 改动 | 需要 sync？ |
| --- | --- |
| 只改 C/C++ | ❌（deploy 脚本会顺带跑，无副作用） |
| 改 Java / 新增插件方法 / 改插件目录结构 | ✅（sync 更新 Gradle/SPM 里的插件引用） |
| 只改 `src/definitions.ts` | ❌（TS 只在 web 侧） |

### 一个绕不过去的事实

`.so` 是在 **APK 打包时**放进 `lib/<abi>/` 的，所以：

- **不能**只把新编的 `.so` 推到非 root 手机替换（`/data/app/...` 是只读的）；
- 可靠做法就是**重新构建 + `adb install -r`**（`android:deploy` 就是这么做的）。
## 验证方式

```bash
# TS
cd plugins/capacitor-cpp-bridge && npm install && npm run build

# Android（会触发 NDK/CMake 编译）
cd android && ./gradlew :capacitor-cpp-bridge:assembleDebug

# 整机验证（真机 / 模拟器）
npm run android:deploy -- -Device <deviceId>
# 然后在测试页点「内存块自检（4MB）」，或跑 autotest / smoke

# iOS（本机无 Mac：用 GitHub Actions，SPM 会自动编译 C++ target）
npm run verify:ios
```

## 发布形态

- **源码 + 构建脚本（推荐）**：使用方需要装 NDK；更新 C++ 代码直接重新编译。
- 预编译 `.so`（可选，本文档不采用）：把各 ABI 的 `.so` 放进 `android/src/main/jniLibs/<abi>/`，
  使用方无需 NDK，但仓库体积变大，且必须覆盖 `arm64-v8a`。
