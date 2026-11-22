# 从零制作一个 Capacitor 插件（Android / iOS / Web）

> 这份文档来自本仓库 13 个自研插件的实践（native-files、data-channel、cpp-bridge、
> back-button 等），覆盖"从空目录到能跑在真机上"的完整流程、必备知识和踩过的坑。

---

## 0. 先理解：一个 Capacitor 插件是什么

一个 Capacitor 插件 = **一个 npm 包** + **一层 TypeScript 接口** + **三端原生实现**：

```
JS/TS 调用
   │  Capacitor 运行时把调用序列化成 { callbackId, pluginId, methodName, options }
   ▼
原生 Bridge（Android: 反射找 @PluginMethod；iOS: 查 pluginMethods 表）
   ▼
你的原生插件代码（Java / Swift）
   ▼
call.resolve({...}) / call.reject("...")  →  JS 侧 Promise
```

**关键点**：插件的"名字"和"方法"必须在三端一致：

| 端 | 在哪里声明 |
| --- | --- |
| TS | `registerPlugin<XxxPlugin>('Xxx', ...)` |
| Android | `@CapacitorPlugin(name = "Xxx")` |
| iOS | `public let jsName = "Xxx"` |

对不上就会出现 `"Xxx" is not implemented on android/ios`。

一个插件目录长这样：

```
capacitor-xxx/
├── package.json          # npm 包定义（含 capacitor 字段，cap sync 靠它发现插件）
├── tsconfig.json
├── src/
│   ├── definitions.ts    # 给用户看的 TypeScript 接口 + JSDoc
│   ├── index.ts          # registerPlugin(...)
│   └── web.ts            # 浏览器降级实现
├── android/
│   ├── build.gradle      # namespace / 依赖
│   ├── gradle.properties
│   ├── settings.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/example/xxx/XxxPlugin.java
├── ios/
│   └── Sources/XxxPlugin/XxxPlugin.swift
├── Package.swift         # iOS SPM 定义
├── README.md
└── .gitignore
```

Capacitor 发现插件的机制：`npx cap sync` 会扫描宿主 `package.json` 依赖里所有带
`"capacitor": { "android": { "src": "android" }, "ios": { "src": "ios" } }` 的包，
然后写进 `android/capacitor.settings.gradle` 和 iOS 的 `CapApp-SPM/Package.swift`。

---

## 1. 环境准备

| 需要 | 用途 |
| --- | --- |
| Node 18+ / npm | 构建 TS、运行 Capacitor CLI |
| Android Studio（含 SDK、**JDK 21**） | Android 构建 |
| Xcode（或 GitHub Actions macOS） | iOS 构建；Windows 上只能靠 CI |
| 一台 Android 真机 / 模拟器 | 验证 |

> **JDK 坑**：AGP 8.x 要求 JDK 17+，如果插件/项目的 `compileOptions` 写了
> `JavaVersion.VERSION_21`，就必须用 **JDK 21** 跑 Gradle。Windows 上最省事的是把
> `JAVA_HOME` 指向 Android Studio 自带的 JBR：
> `C:\Program Files\Android\Android Studio\jbr`。

开发方式建议：**在宿主项目的 `plugins/` 里开发**，用 `file:` 依赖引入，改完 `npx cap sync` 即可。

---

## 2. 创建骨架

### 方式 A：官方生成器

```bash
npm init @capacitor/plugin@latest
```

按提示填包名、作者等，会生成 Android/iOS/TS 骨架。适合快速起步。

### 方式 B：手动创建（推荐先跟着做一遍）

在 `plugins/capacitor-hello/` 下创建下面这些文件（内容见第 9 节完整示例）：

1. `package.json`
2. `tsconfig.json`
3. `src/definitions.ts`
4. `src/index.ts`
5. `src/web.ts`
6. `android/build.gradle`
7. `android/gradle.properties`
8. `android/settings.gradle`
9. `android/src/main/AndroidManifest.xml`
10. `android/src/main/java/.../HelloPlugin.java`
11. `Package.swift`
12. `ios/Sources/HelloPlugin/HelloPlugin.swift`
13. `README.md` + `.gitignore`

---

## 3. JS 层：接口、注册、Web 降级

### 3.1 `src/definitions.ts`

这是**插件的公开契约**，也是用户唯一的类型来源：

```ts
import type { PluginListenerHandle } from '@capacitor/core';

export interface HelloPlugin {
  /** 打个招呼。 */
  greet(options: { name: string; times?: number }): Promise<{ message: string; count: number }>;

  /** 监听事件。 */
  addListener(
    eventName: 'greeted',
    listenerFunc: (event: { message: string }) => void,
  ): Promise<PluginListenerHandle>;
}
```

**要求**：

- 每个方法都返回 `Promise<...>`；
- 参数用 `options` 对象（便于以后加字段，不破坏兼容）；
- 字段用 JSDoc 写清楚含义、默认值、平台差异（用户会直接看 IDE 提示）。

### 3.2 `src/index.ts`

```ts
import { registerPlugin } from '@capacitor/core';
import type { HelloPlugin } from './definitions';

const Hello = registerPlugin<HelloPlugin>('Hello', {
  web: () => import('./web').then((m) => new m.HelloWeb()),
});

export * from './definitions';
export { Hello };
```

**`'Hello'` 必须和 Android/iOS 的插件名完全一致。**

### 3.3 `src/web.ts`

浏览器环境没有原生能力，需要一个降级实现（至少让 import 不报错）：

```ts
import { WebPlugin } from '@capacitor/core';
import type { HelloPlugin } from './definitions';

export class HelloWeb extends WebPlugin implements HelloPlugin {
  async greet({ name, times = 1 }: { name: string; times?: number }) {
    return { message: `Hello ${name}`.repeat(times), count: times };
  }
}
```

### 3.4 `package.json` 的关键字段

```json
{
  "name": "capacitor-hello",
  "version": "0.1.0",
  "main": "dist/esm/index.js",
  "types": "dist/esm/index.d.ts",
  "files": [
    "android/src/main/",
    "android/build.gradle",
    "ios/Sources",
    "Package.swift",
    "dist/"
  ],
  "scripts": { "build": "tsc" },
  "peerDependencies": { "@capacitor/core": ">=8.0.0" },
  "capacitor": { "android": { "src": "android" }, "ios": { "src": "ios" } }
}
```

- **`files`** 决定 `npm pack`/发布时带哪些文件（`dist` 必须包含）；
- **`capacitor`** 字段是 `cap sync` 发现插件的依据，**不能漏**；
- **`peerDependencies`** 建议写 `@capacitor/core >= 8`（或你实际兼容的最低版本）。

---

## 4. Android 层

### 4.1 插件类

```java
@CapacitorPlugin(name = "Hello")
public class HelloPlugin extends Plugin {

    @PluginMethod
    public void greet(PluginCall call) {
        String name = call.getString("name", "world");
        Integer times = call.getInt("times", 1);

        JSObject ret = new JSObject();
        ret.put("message", "Hello " + name);
        ret.put("count", times);

        notifyListeners("greeted", ret);  // 可选：派发事件
        call.resolve(ret);
    }
}
```

### 4.2 参数与返回值类型映射

| JS 传入 | Java 取法 | 注意 |
| --- | --- | --- |
| `string` | `getString(key, default)` | 缺失返回 default/null |
| `number` | `getInt` / `getDouble` / `getFloat` | 小数用 Double/Float |
| `boolean` | `getBoolean` | |
| `object` | `getObject(key)` → `JSObject` | |
| `array` | `getArray(key)` → `JSArray` | |
| `null` | `isNull(key)` | |

返回：`call.resolve(JSObject)`；失败：`call.reject("message", "code", exception)`。

### 4.3 大整数坑（真实踩过）

**`PluginCall.getLong()` 只接受 JSON 里的 `Long`**。JS 传的小整数在 `org.json` 里是
`Integer`，`getLong` 会**静默返回默认值**（不报错！），导致 `offset`/`maxBodyBytes` 参数失效。

修复方式：从 `Number`/`String` 自己转：

```java
private long readLong(PluginCall call, String key, long fallback) {
    Object raw = call.getData().opt(key);
    if (raw instanceof Number) return ((Number) raw).longValue();
    if (raw instanceof String) try { return Long.parseLong((String) raw); } catch (Exception ignored) {}
    return fallback;
}
```

### 4.4 线程模型

- 插件方法默认跑在 Capacitor 的**串行后台线程**；
- 操作 UI（WebView / Activity / Dialog）必须切主线程：

```java
getActivity().runOnUiThread(() -> { /* UI 操作 */ call.resolve(ret); });
```

- `getBridge().getWebView()`、`getActivity()` 提供原生对象。

### 4.5 生命周期

```java
@Override public void load() { /* 插件初始化，可注册回调 */ }
@Override protected void handleOnDestroy() { /* 释放资源 */ }
@Override protected void handleOnNewIntent(Intent intent) { }
@Override protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) { }
```

### 4.6 权限

```java
@CapacitorPlugin(
    name = "Hello",
    permissions = { @Permission(alias = "camera", strings = { Manifest.permission.CAMERA }) }
)
public class HelloPlugin extends Plugin {

    @PluginMethod
    public void openCamera(PluginCall call) {
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "cameraCallback");
        } else {
            actuallyOpen(call);
        }
    }

    @PermissionCallback
    private void cameraCallback(PluginCall call) {
        if (getPermissionState("camera") == PermissionState.GRANTED) actuallyOpen(call);
        else call.reject("camera permission denied");
    }
}
```

### 4.7 Manifest

把权限/组件写在插件的 `android/src/main/AndroidManifest.xml`，构建时会**自动合并**进宿主 App：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA" />
</manifest>
```

> Android 12+ 的 `receiver`/`service` 必须显式 `android:exported`（有 intent-filter 时通常 `true`）。

### 4.8 `android/build.gradle`

```gradle
android {
    namespace = "com.example.hello"
    compileSdk = 36
    defaultConfig { minSdkVersion 24; targetSdkVersion 36 }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_21
        targetCompatibility JavaVersion.VERSION_21
    }
}
dependencies {
    implementation project(':capacitor-android')
    implementation "androidx.appcompat:appcompat:1.7.1"
}
```

`settings.gradle` 固定写：

```gradle
include ':capacitor-android'
project(':capacitor-android').projectDir = new File('../../../../node_modules/@capacitor/android/capacitor')
```

---

## 5. iOS 层

### 5.1 插件类

```swift
import Foundation
import Capacitor

@objc(HelloPlugin)
public class HelloPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "HelloPlugin"
    public let jsName = "Hello"

    // ⚠️ 每个方法都必须列出来，否则 JS 调用会 "not implemented"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "greet", returnType: CAPPluginReturnPromise)
    ]

    @objc public func greet(_ call: CAPPluginCall) {
        let name = call.getString("name", "world")
        let times = call.getInt("times", 1)
        call.resolve([
            "message": "Hello \(name)",
            "count": times
        ])
    }
}
```

### 5.2 参数类型

- `getString(_:default:)`、`getInt`、`getBool`、`getDouble`、`getObject`；
- `getValue(key)` 返回原始 `JSValue`（大整数/嵌套结构用）；
- iOS 的 `JSObject` 实际是 `[String: JSValue]`，**不存在 `getData()`**（我们踩过）。

### 5.3 线程

Capacitor iOS 会把插件方法调到主线程；耗时任务：

```swift
DispatchQueue.global().async {
    let result = doHeavyWork()
    DispatchQueue.main.async { call.resolve(["result": result]) }
}
```

### 5.4 访问 WebView / 配置

```swift
bridge?.webView            // WKWebView
bridge?.config             // InstanceConfiguration（serverURL、appId 等）
bridge?.viewController     // 当前 VC
```

### 5.5 事件

```swift
notifyListeners("greeted", data: ["message": "hi"])
```

### 5.6 `Package.swift`

```swift
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorHello",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "CapacitorHello", targets: ["HelloPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "HelloPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/HelloPlugin")
    ]
)
```

**注意**：

- 文件名/目录名要跟 `path` 对上；
- **一个 target 不能混 Swift 和 C/C++**。如果插件里有 C/C++，要拆成两个 target
  （C 核心 target + Swift 桥 target，Swift target 依赖 C target），并在 package 层加
  `cxxLanguageStandard: .cxx17`（不要用 `unsafeFlags`）；
- 产品名（product）在宿主 App 里必须唯一，避免和其它插件冲突。

---

## 6. 接入宿主项目

```bash
# 1) 插件先构建出 dist（没有 prepare 脚本时 npm install 不会自动 build）
cd plugins/capacitor-hello
npm install
npm run build

# 2) 在宿主项目根安装（file: 方式）
npm install ./plugins/capacitor-hello
# 或 package.json: "capacitor-hello": "file:plugins/capacitor-hello" 再 npm install

# 3) 同步到原生工程
npx cap sync

# 4) 构建 App
# Android: npm run android:deploy / gradlew assembleDebug
# iOS: Xcode 或 CI
```

验证接线：

```bash
# Android: android/capacitor.settings.gradle 里应出现插件模块
# iOS: ios/App/CapApp-SPM/Package.swift 里应出现你的包名
```

### CLI 是怎么找到你的插件类的（两个隐蔽规则）

`cap sync` 不是靠配置声明找类，而是靠**正则扫描源码**（因此格式错了会"找不到插件"，不报错）：

1. **Java 文件的 `package x.y;` 和 `@CapacitorPlugin(...)` 必须顶格写**（行首不能有空格）。
   CLI 用 `^@(?:CapacitorPlugin|NativePlugin)[\s\S]+?class (\w+)` 和 `^package ([\w.]+);?$` 定位类名和包名，
   带缩进就匹配不到。Gradle 模块名 = 包名去掉 `@`、`/` 换 `-`。
2. **iOS SPM 包名由 CLI 从 npm 包名推导**：只有首字母和 `-`/`_` 后的字母大写。
   `capacitor-native-statusbar` → `CapacitorNativeStatusbar`（**不是** `CapacitorNativeStatusBar`）；
   `Package.swift` 的 `name` 必须等于这个推导名，否则 `CapApp-SPM/Package.swift` 引用不到包（编译报找不到）。

---

## 7. 调试

| 手段 | 位置 |
| --- | --- |
| JS console | `adb logcat -s Capacitor/Console`（Android）、Xcode 控制台（iOS） |
| 原生日志 | `Log.d("Hello", ...)` / `CAPLog.print(...)` |
| WebView 调试 | Chrome `chrome://inspect`（Android）；Safari 开发菜单（iOS） |
| 方法没被调用 | 检查三端名字是否一致、iOS `pluginMethods` 是否漏、`cap sync` 是否跑过 |
| 参数不对 | 在原生里打印 `call.getData().toString()` / `call.options` |

---

## 8. 常见坑（按出现频率）

1. **`jsName` 不一致** → `"Xxx" is not implemented`。三端必须一模一样。
2. **Android 方法漏 `@PluginMethod`** → 运行时找不到方法。
3. **iOS 方法漏进 `pluginMethods` 数组** → 只有 iOS 报 not implemented（Android 正常）。
4. **`getLong()` 静默返回默认值**（见 4.3）→ 参数"没生效"。
5. **在后台线程操作 UI** → 崩溃或 `CalledFromWrongThreadException`；用 `runOnUiThread`。
6. **`PluginCall` 只能 resolve/reject 一次**；重复调用会抛异常。
7. **大文件走 JS 桥** → base64/JSON 又慢又占内存（实测约 10 MB/s）；用文件、HTTP、本地 server。
8. **JNI/C++**：`JNIEnv*` 线程私有，跨线程要 `AttachCurrentThread`；异常不能穿过 JNI。
9. **Manifest 合并**：权限/组件写插件 Manifest，宿主无需重复；`exported` 在 Android 12+ 必填。
10. **iOS 权限**：`Info.plist` 的 usage description 必须写，否则一调用就崩。
11. **iOS SPM 不能混 Swift/C++** → 拆 target。
12. **忘记构建插件的 `dist`** → `Cannot find module 'capacitor-xxx'`。
13. **忘记 `npx cap sync`** → 原生侧没有插件。
14. **`file:` 依赖是 junction**（Windows）→ 压缩/移动项目后可能失效，重新 `npm install`。
15. **JDK 版本**：AGP/Gradle/`compileOptions` 三者要匹配；`invalid source release: 21` 就是 JDK 太老。
16. **NDK 版本写死**：CMake 项目固定 `ndkVersion`，换机器要装同一版本。
17. **Live Reload 的 origin 变化**：页面变成 `http://host:port`，做 Origin 校验的插件要额外放行（见 data-channel 的 `debug`）。
18. **Live Reload 下 `convertFileSrc` 失效**：`_capacitor_file_` 由原生服务提供，dev server 没有该路由。
19. **`adb reverse` 会丢**：切换 USB 模式/拔线后要重新加，HMR 也会断。
20. **iOS 不能主动退出 App**：`exitApp` 会 reject；不要设计"退出"按钮。
21. **后台/开机自启**：iOS 完全做不到；Android 只能保活不能自动弹界面。
22. **测试只在一端**：Android 能跑不代表 iOS 能编译；至少跑一次 CI。
23. **版本号**：每次改动 bump `package.json` 的 version，否则 `file:` 依赖不容易判断新旧。
24. **`private: true`**：如果要发 npm，别写；如果只内部用，写上也行。

---

## 9. 完整最小示例：`Hello` 插件

### `src/definitions.ts`

```ts
import type { PluginListenerHandle } from '@capacitor/core';

export interface HelloPlugin {
  greet(options: { name?: string; times?: number }): Promise<{ message: string; count: number }>;
  addListener(
    eventName: 'greeted',
    listenerFunc: (event: { message: string }) => void,
  ): Promise<PluginListenerHandle>;
}
```

### `src/index.ts`

```ts
import { registerPlugin } from '@capacitor/core';
import type { HelloPlugin } from './definitions';

const Hello = registerPlugin<HelloPlugin>('Hello', {
  web: () => import('./web').then((m) => new m.HelloWeb()),
});

export * from './definitions';
export { Hello };
```

### `src/web.ts`

```ts
import { WebPlugin } from '@capacitor/core';
import type { HelloPlugin } from './definitions';

export class HelloWeb extends WebPlugin implements HelloPlugin {
  async greet({ name = 'world', times = 1 }: { name?: string; times?: number }) {
    const message = Array.from({ length: times }, () => `Hello ${name}`).join(', ');
    this.notifyListeners('greeted', { message });
    return { message, count: times };
  }
}
```

### Android

```java
@CapacitorPlugin(name = "Hello")
public class HelloPlugin extends Plugin {
    @PluginMethod
    public void greet(PluginCall call) {
        String name = call.getString("name", "world");
        Integer times = call.getInt("times", 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            if (i > 0) sb.append(", ");
            sb.append("Hello ").append(name);
        }
        JSObject ret = new JSObject();
        ret.put("message", sb.toString());
        ret.put("count", times);
        notifyListeners("greeted", ret);
        call.resolve(ret);
    }
}
```

### iOS

```swift
@objc(HelloPlugin)
public class HelloPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "HelloPlugin"
    public let jsName = "Hello"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "greet", returnType: CAPPluginReturnPromise)
    ]

    @objc public func greet(_ call: CAPPluginCall) {
        let name = call.getString("name", "world")
        let times = call.getInt("times", 1)
        let message = (0..<times).map { _ in "Hello \(name)" }.joined(separator: ", ")
        notifyListeners("greeted", data: ["message": message])
        call.resolve(["message": message, "count": times])
    }
}
```

---

## 10. 发布前检查清单

- [ ] 三端名字一致（TS / Java / Swift）
- [ ] TS 接口覆盖所有原生 `@PluginMethod`（可用本仓库的 `npm run audit:plugins` 思路做检查）
- [ ] iOS `pluginMethods` 一个不少
- [ ] Android / iOS / Web 都能构建
- [ ] 真机验证核心路径
- [ ] README：安装、用法、API 字段、平台差异、已知限制
- [ ] `package.json`：version、files、capacitor、peerDependencies
- [ ] 清理 `node_modules` / `dist` / `android/build`（要归档时）
- [ ] iOS CI 至少跑通一次编译

---

## 附：本仓库可参考的插件

| 插件 | 适合学习什么 |
| --- | --- |
| `capacitor-native-basics` | 最小骨架、Toast、设备信息 |
| `capacitor-native-statusbar` | 系统 UI 操作、暗色模式 |
| `capacitor-native-files` | 权限、SAF、文件读写、23 个目录 API |
| `capacitor-data-channel` | 本地 HTTP/WS 服务、OTP、二进制传输、Live Reload 适配 |
| `capacitor-cpp-bridge` | JNI / Swift C 互操作、CMake + SPM、零拷贝 |
| `capacitor-back-button` | 事件、生命周期、Android 返回键、iOS WKWebView 手势 |
| `capacitor-permissions` | Capacitor `@Permission` 别名、iOS 各框架授权、串行弹窗 |
| `capacitor-secure-storage` | Keychain / AndroidKeyStore / WebCrypto、生物识别（CryptoObject / SecAccessControl） |
| `capacitor-background-keepalive` | Android 前台服务与 FGS 类型限制、iOS 后台执行的真实边界 |
| `capacitor-live-update` | 自定义 zip 解析（Compression 框架）、ECDSA 验签、`setServerBasePath`、OTA 回滚 |
