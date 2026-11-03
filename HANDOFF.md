# 项目上下文存档（HANDOFF）

> **用途**：这份文件保存了截至 2026-11-03 本对话中掌握的所有关键信息——本机环境、项目结构、
> 已完成的 6 个自定义 Capacitor 插件、插件开发手册、验证体系、踩过的坑。
> **用法**：开新对话后，第一句让我「读一下根目录的 HANDOFF.md」，即可快速重建上下文继续工作。

---

## 1. 项目概览

- 项目：`D:\projs\testionic`（学习用 demo，可以随便造）
- 技术栈：**Capacitor 8.6.0** + **Vue 2.7** + vue-router 3（hash 模式）+ **Vite 3** + `@vitejs/plugin-legacy`（targets IE11）
- 平台：Android（真机 + 模拟器）+ iOS（只有 CI，无 Mac）+ Web
- `capacitor.config.json`：appId `com.myapp.testionic`，webDir `dist`（**没有** plugins 配置，保持默认）
- Git 仓库：https://github.com/Cinichengbuweikong/tmp_testionic.git（公开），分支 `main`
- 目标：用户是 Web 前端（Vue2），不会原生开发；由 AI 代写原生插件。**插件日后要拿走复用**，所以每个插件都是独立 npm 包（`file:` 引用）

## 2. 本机开发环境（2026-11-02 实测）

| 项 | 值 |
| --- | --- |
| 系统 | Windows 10（19045） |
| 磁盘 | C: 约 57GB 可用；D: 约 34GB 可用 |
| Node / npm | v24.17.0 / 11.13.0（无 pnpm/yarn）；registry = 官方 npmjs |
| **JAVA_HOME** | ⚠️ Temurin **JDK 17**（不满足 Capacitor 8） |
| **JDK 21** | Android Studio 自带 JBR 21.0.10：`C:\Program Files\Android\Android Studio\jbr` |
| Android Studio | Otter 代（Capacitor 8 要求 AS ≥ 2025.2.1） |
| Android SDK | `C:\Users\cinic\AppData\Local\Android\Sdk`（`ANDROID_HOME` 已设置） |
| SDK Platform | android-36、android-36.1；build-tools 35.0.0 / 36.0.0 / 36.1.0 / 37.0.0 |
| Gradle | wrapper 8.14.3（腾讯云镜像下载）；AGP 8.13.0；compileSdk 36 / minSdk 24 / targetSdk 36 |
| adb | 37.0.0；Git 2.45.2 |
| iOS | **没有 Mac**。编译验证全部走 GitHub Actions 的 `macos-26` runner（Xcode 26） |

### ⚠️ 最大的坑：JDK 版本

`android/app/capacitor.build.gradle` 由 Capacitor 生成，写死 Java 21：

```gradle
sourceCompatibility JavaVersion.VERSION_21
targetCompatibility JavaVersion.VERSION_21
```

系统 `JAVA_HOME` 是 JDK 17，直接跑 gradlew 会失败。两种办法：

1. 用脚本：`npm run android:deploy`（内部自动把 JAVA_HOME 指向 JBR 21）；
2. 手动：`$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'` 后再跑 gradlew；
3. 或用 Android Studio 打开 `android/`（AS 默认用自己的 JBR 21）。

## 3. 设备与测试环境

| 设备 | 信息 | 备注 |
| --- | --- | --- |
| 真机 | Redmi K30S Ultra（M2007J3SC），Android 10 / API 29 / **MIUI 12**，arm64-v8a，serial `32e93a6d` | WebView **87.0.4280.101**；系统默认深色模式 |
| 模拟器 | AVD `Pixel_6`（android-36 default x86_64），serial `emulator-5554` | WebView **133.0.6943.137**；软件渲染 |

- 写这份文档时**真机未连接**（`adb devices` 为空）；恢复工作前先让用户插上手机并确认「开发者选项 → USB 安装」已放行（否则 `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED`）。
- 模拟器无窗口启动（当前没在跑）：
  ```powershell
  emulator -avd Pixel_6 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
  ```
  首启会弹 “System UI isn't responding”，点 Wait；启动约 1 分钟。
- 手机 App 包名 `com.myapp.testionic`；曾被卸载后重装为 debug 包。release 签名文件在 `android/key/key`（密码未知，**已 gitignore**）。

## 4. 目录结构速览

```
D:\projs\testionic
├─ plugins/                      # 6 个自研插件（每个都是独立 npm 包）
│  ├─ capacitor-native-basics/      # 参考实现：echo / showToast / getDeviceInfo
│  ├─ capacitor-native-statusbar/   # 状态栏背景色 + 图标亮暗
│  ├─ capacitor-rotation-listener/  # 屏幕旋转监听（getRotation + rotationChanged 事件）
│  ├─ capacitor-set-force-dark/     # WebView forceDark 修复 + 主题注入
│  ├─ capacitor-system-info/        # 屏幕/系统/安全区等 13 个独立只读 API
│  ├─ capacitor-webview-to-edge/    # WebView 铺满整屏 + 安全区 insets
│  └─ README.md                     # 插件集合说明 + CLI 识别规则
├─ src/
│  ├─ main.js                     # VITE_AUTOTEST=1 时自动跳 /plugin-test?autotest=1
│  ├─ router/index.js             # hash 路由：/ /about /plugin-test
│  ├─ views/PluginTestView.vue    # 「插件自检」页：6 个插件的按钮 + autotest 逻辑
│  └─ assets/base.css             # 深色配色（⚠️ 有未提交改动，见第 10 节）
├─ scripts/
│  ├─ deploy-android.ps1          # 一键构建+安装+启动（JBR21，可 -Device 指定设备）
│  ├─ verify-android.ps1          # 真机+模拟器 CDP 冒烟测试（PASS/FAIL 汇总）
│  ├─ build-plugins.mjs           # 逐个构建 plugins/ 下的插件（本地与 CI 共用）
│  ├─ webview-eval.mjs            # 通过 WebView DevTools 协议在页面里执行 JS
│  ├─ smoke-test.js               # 冒烟脚本：DOM 点按钮、读 <pre> 结果并断言
│  └─ verify-ios.mjs              # iOS 静态检查（本机可跑，不能替代 Xcode）
├─ android/                       # Capacitor Android 工程（国内 Maven 镜像已配）
├─ ios/                           # Capacitor iOS 工程（SPM：CapApp-SPM）
├─ .github/workflows/ios.yml      # iOS CI：编译 + 模拟器运行 + autotest 断言
├─ docs/
│  ├─ development-environment.md  # 环境档案（更详细）
│  └─ ios-without-mac.md          # 无 Mac 做 iOS 的方案 + 云 Mac 报价
└─ capacitor.config.json          # 只有 appId/appName/webDir，无插件配置
```

## 5. 插件清单与 API 速查

### 5.1 capacitor-native-basics（参考实现）

- Android 包 `com.testionic.nativebasics`；iOS 包 `CapacitorNativeBasics`
- API：`echo({value})`、`showToast({text, duration?})`、`getDeviceInfo()`
- `DeviceInfo`：`manufacturer / model / osVersion / platform('android'|'ios'|'web') / sdkInt?(仅Android) / isEmulator`
- 作用：验证插件骨架、作为新插件复制的模板

### 5.2 capacitor-native-statusbar（状态栏样式）

- Android 包 `com.testionic.nativestatusbar`；iOS 包 `CapacitorNativeStatusbar`
- API：
  - `setBackgroundColor({ color })`：支持 `#RGB / #RRGGBB / #AARRGGBB(ARGB顺序) / transparent`
  - `setStyle({ style: 'light' | 'dark' | 'default' })`
  - `getInfo()` → `{ color, style, height, usingOverlay }`
- **核心实现**（Android）：`window.setStatusBarColor()` 在 Android 15+ 与 MIUI 上无效，
  所以在 DecorView 顶部**自绘等高色块**：`topMargin = -decorView.paddingTop` + `setClipToPadding(false)` + `bringToFront()`；
  有色块时**不要再调** `setStatusBarColor`（MIUI 会因此重算图标颜色）。
- **图标亮暗**：`WindowInsetsControllerCompat` + 旧 `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` 双通道 + 350ms 补一次；
  **MIUI 12 会按背景色自动对比度**（蓝底→白图标、白底→深图标），显式 light/dark 会被覆盖（已实测，
  官方插件同样无效，0/5320 像素差异对比）；模拟器（非 MIUI）上正常生效。
- iOS：自绘 UIView（高度 = safeArea top），`CAPBridgeViewController.setStatusBarStyle()`。
- 未调用 `setStyle` 前不主动改样式（`styleApplied` 门控）。

### 5.3 capacitor-set-force-dark（WebView forceDark 修复）

- Android 包 `com.testionic.setforcedark`；iOS 包 `CapacitorSetForceDark`；jsName `SetForceDark`
- **注意：它不控制系统的深色模式**，只管 WebView 里的页面显示。
- API：
  - `setTheme({ theme: 'system' | 'light' | 'dark' })` → `{ requestedTheme, isDark, isForced }`
  - `getTheme()`（副作用：会再注入一次页面）
  - `setForceDark({ enabled })`（默认 false = 关闭算法反色）
  - `getInfo()` → `{ ...主题, forceDarkEnabled, themeInjected, webViewUserAgent }`
  - `addListener('themeChanged', cb)`：`setTheme` 时、系统深浅色切换且 mode=system 时触发（Android 已去重：仅 isDark 变化才发）
- **行为**：注入脚本始终把 `<html data-theme="light|dark">` 与 `color-scheme` 写上（**亮色也是 data-theme="light"，不是没有属性**）；
  首次注入时把 `@media (prefers-color-scheme: dark)` 的规则 CSSOM 重写成 `html[data-theme="dark"]`（兼容不改 CSS 的老项目）。
- **根因**：MIUI 的 WebView 不报告 prefers-color-scheme（App Configuration 是 night，但 matchMedia dark = false），
  页面按亮色渲染后被 MIUI forceDark 反色 → 颜色全错。解法=关 forceDark + 注入主题。
- iOS：`overrideUserInterfaceStyle`（真会翻转 prefers-color-scheme）；没有 forceDark，`setForceDark` 为空操作。

### 5.4 capacitor-webview-to-edge（WebView 铺满整屏）

- Android 包 `com.testionic.webviewtoedge`；iOS 包 `CapacitorWebviewToEdge`；jsName `WebViewToEdge`
- **不是隐藏系统栏的“全屏模式”**，只是内容延伸到状态栏/导航栏下面；默认关闭，纯 API 控制（无需改 config）。
- API：`enable() / disable() / setEnabled({enabled}) / isEnabled() / getInsets() / addListener('insetsChanged', cb)`
- `getInsets()` → `{ top, right, bottom, left }`（CSS px）；同时自动注入 CSS 变量 `--safe-area-inset-*`。
- Android：接管 DecorView 的 `OnApplyWindowInsetsListener`（后装覆盖 Capacitor `SystemBars` 的）；
  enable → padding 全 0（键盘弹出时保留 IME 底部）；disable → 恢复系统栏 padding；
  系统栏 insets 清零后回传（所以 **Android 上 `env(safe-area-inset-*)` 恒为 0**，统一用插件变量）。
- iOS：Capacitor 默认已贴边（`contentInsetAdjustmentBehavior = .never`）；enable=.never、disable=.automatic；安全区来自 `safeAreaInsets`。
- 实测：真机 WebView bounds `[0,90]` → enable `[0,0]` → disable `[0,90]`；模拟器 `[0,128]` → `[0,0][1080,2400]`，insets top 49 / bottom 48。

### 5.5 capacitor-system-info（系统与硬件信息）

- Android 包 `com.testionic.systeminfo`；iOS 包 `CapacitorSystemInfo`；jsName `SystemInfo`
- **设计原则（用户明确要求）**：**13 个独立只读 API，问什么收集什么、互不影响**，不搞一个大 `getInfo()`。
- API：`getScreenInfo / getViewport / getSafeAreaInsets / getStatusBarInfo / getNavigationBarInfo /
  getCutoutInfo / getSystemInfo / getWebViewInfo / getMemoryInfo / getStorageInfo / getBatteryInfo /
  getNetworkInfo / getAppInfo`，全部无参数、返回 Promise。
- 单位约定：`px`=物理像素；`css`=CSS px（Android dp / iOS pt）；`css × density = px`。
  `getViewport().css` 就是 CSS 里 `100%` 的大小。
- **关键实现（Android）**：
  - API 30+ 从 `WindowManager.getMaximumWindowMetrics()` 拿系统栏真实值（显示层数据，
    绕开 webview-to-edge 常驻的 insets 监听器）；
  - API 29 **底部导航栏用 `getWindowVisibleDisplayFrame()` 反推**——MIUI 手势模式下
    insets 假报三键 130px（stable/transient 都是），但窗口实际铺满（dumpsys `mStable=[0,90][1080,2400]`、
    to-edge 收到 `{top:33,bottom:0}` 三方印证）；反推后正确报 0；
  - 导航模式优先读 `settings navigation_mode`（MIUI 读不到）→ 实测占用推断；
  - 状态栏高度含挖孔：K30S 挖孔 `[0,141]×[0,90]` → 状态栏 90px/32.73css。
- 实测数据（2026-11-03）：
  | 字段 | 真机 K30S（MIUI12/API29/WebView87） | 模拟器 Pixel_6（API36/WebView133） |
  | --- | --- | --- |
  | screen | 1080×2400 px / 392.73×872.73 css；density 2.75 (440dpi) | 1080×2400 px / 411.43×914.29 css；density 2.625 (420dpi) |
  | viewport（默认） | 1080×2310 px / 840 css（只让出状态栏） | 1080×2146 px / 817.52 css（让出状态栏+三键） |
  | statusBar | 90px / 32.73css | 128px / 48.76css |
  | navigationBar | 0（手势模式） | 126px / 48css，three_button |
  | safeArea | top 32.73 / bottom 0（css；px 90/0） | top 48.76 / bottom 48（css；px 128/126） |
  | refreshRate | 90（用户档位，读 MIUI `user_refresh_rate`；max 144） | 60（max 60） |
- iOS：`window.safeAreaInsets`（状态栏高度=safeArea.top） / `NWPathMonitor`（1.5s 超时兜底）/
  `os_proc_available_memory()` / `utsname` 硬件型号；导航栏=Home Indicator；电池模拟器=-1；
  WebView 版本取 UA `Version/x.y`。
- 两个 Capacitor 8 API 坑：`Plugin.getConfig()` 只含**插件自己的配置块**（`plugins.Xxx` 节点），
  读不到全局 appName → 从 application label 读；`Plugin.getAppId()` 是基类自带方法。
- Android 权限：仅 `ACCESS_NETWORK_STATE`（普通权限，无弹窗）。

### 5.6 capacitor-rotation-listener（屏幕旋转监听）

- Android 包 `com.testionic.rotation`；iOS 包 `CapacitorRotationListener`；jsName `Rotation`；
  npm 包名 `capacitor-rotation-listener`（用户指定）。
- API：`getRotation()` → `RotationState { orientation, rotationDegrees, isPortrait, isLandscape }`；
  `addListener('rotationChanged', cb)`（返回 handle，`handle.remove()` 解除）；
  `removeAllListeners()`（桥内置）。**监听/解除监听不需要原生实现，桥本身支持**。
- **Android**：事件源 = `Plugin.handleOnConfigurationChanged`（要求宿主 MainActivity 的
  `android:configChanges` 含 orientation|screenSize，Capacitor 模板默认已含）；用「orientation +
  rotationDegrees」双值去重，键盘/字体等非方向配置变化不派发。
- **iOS**：`UIDevice.orientationDidChangeNotification`（load 时 beginGenerating，deinit 时注销）；
  orientation 用 WebView 宽高比判断（faceUp 也可靠），角度由 UIDevice 映射
  （portrait→0 / upsideDown→180 / landscapeRight→90 / landscapeLeft→270）；不用 iOS 26 已
  obsoleted 的 `windowScene.interfaceOrientation`。
- **Web**：`screen.orientation change` / `orientationchange` 模拟，同构派发事件。
- 实测（2026-11-03）：模拟器 `settings put system user_rotation` 横屏→`landscape/90`、回竖屏→
  `portrait/0`、**解除监听后再转不触发**；`adb emu rotate` → `landscape/270`；真机 MIUI 12 →
  `landscape/90` 正常。
- 坑：`JSObject.getInt(String,int)` 不存在（org.json 只有单参），要用 `optInt(name, default)`。

## 6. Capacitor 8 插件开发手册（要点）

### 6.1 包结构（每个插件）

```
plugins/<name>/
├─ package.json        # 必须有 "capacitor": {"android":{"src":"android"},"ios":{"src":"ios"}}
├─ tsconfig.json       # outDir dist/esm，files: ["src/index.ts"]
├─ Package.swift       # iOS SPM 包（名字必须=CLI 推导名，见 6.3）
├─ src/{definitions.ts, index.ts, web.ts}
├─ android/{build.gradle, settings.gradle, gradle.properties}
│  └─ src/main/java/com/testionic/<pkg>/XxxPlugin.java
└─ ios/Sources/XxxPlugin/XxxPlugin.swift
```

### 6.2 JS 侧

```ts
// index.ts
const Xxx = registerPlugin<XxxPlugin>('Xxx', {
  web: () => import('./web').then((m) => new m.XxxWeb()),
});
```
`addListener` / `removeListener` 是 Capacitor 桥内置的，**不需要写进原生 pluginMethods**（本项目的 `verify-ios.mjs` 已把它们加白名单）。

### 6.3 Capacitor CLI 的识别规则（踩过的坑）

- `package.json` 必须有 `capacitor` 字段，否则 CLI 不把它当插件。
- Java 类里 **`package xxx.yyy;` 和 `@CapacitorPlugin(...)` 必须顶格写**（不能有缩进），
  CLI 用正则 `^@(?:CapacitorPlugin|NativePlugin)[\s\S]+?class (\w+)` + `^package ([\w.]+);?$` 找类名/包名。
- Gradle 模块名 = 包名去掉 `@`、`/` 换 `-`（如 `@scope/foo` → `scope-foo`）。
- **iOS SPM 包名 = CLI 从 npm 包名推导**（只有首字母和分隔符后大写）：
  `capacitor-native-statusbar` → `CapacitorNativeStatusbar`；`capacitor-set-force-dark` → `CapacitorSetForceDark`。
  Package.swift 的 `name` 必须一致，否则 `CapApp-SPM` 引用不到（CI 会红）。
- 插件的 `dist/` 不入库（.gitignore）。**先构建插件再构建应用**：本地/CI 都用 `npm run build:plugins`。
- 插件里 `android/build.gradle` 用 `project.hasProperty(...) ? rootProject.ext.xxx : 兜底值` 取 SDK/依赖版本，Java 21。

### 6.4 Android 实现要点

- 类骨架：
  ```java
  @CapacitorPlugin(name = "Xxx")
  public class XxxPlugin extends Plugin {
      @PluginMethod
      public void someMethod(PluginCall call) { ... call.resolve(ret); /* 或 call.reject(...) */ }
  }
  ```
- **线程**：插件方法跑在名为 `CapacitorPlugins` 的后台线程。所有 View / WebView 操作必须切主线程：
  `getActivity().runOnUiThread(...)` / `getBridge().executeOnMainThread(...)` / `webView.post(...)`。
  特别地，`WebView.getSettings()` 在非主线程调用会直接崩 App（set-force-dark 踩过）。
- 事件：`notifyListeners("eventName", data)`；生命周期钩子：`handleOnStart / handleOnConfigurationChanged / load()`。
- 页面加载钩子：`getBridge().addWebViewListener(new WebViewListener(){ onPageLoaded / onPageCommitVisible })`；
  在页面里执行 JS：`getBridge().getWebView().evaluateJavascript(js, null)`（主线程）。
- 布局/insets 事实：窗口从 y=0 开始，DecorView 用 padding 把内容顶到状态栏下（真机 90px / 模拟器 128px）；
  自绘覆盖状态栏的色块需要 `topMargin=-paddingTop` + `setClipToPadding(false)`。

### 6.5 iOS 实现要点

```swift
@objc(XxxPlugin)
public class XxxPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "XxxPlugin"
    public let jsName = "Xxx"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "someMethod", returnType: CAPPluginReturnPromise)
    ]
    @objc func someMethod(_ call: CAPPluginCall) { call.resolve([...]) }
}
```
- `pluginMethods` 里方法名必须与 `@objc func` 一致；`@objc(XxxPlugin)` 必须有。
- 线程同样要 `DispatchQueue.main.async`。
- 常用：`bridge?.viewController`（可 cast 成 `CAPBridgeViewController`，有 `setStatusBarStyle(_:)`）、`bridge?.webView`；
  `overrideUserInterfaceStyle` 可强制亮/暗；监听主题变化可用隐藏 UIView 子类重写 `traitCollectionDidChange` 或 `layoutSubviews`。
- JS console → 原生日志由 `CAPLog.print`（`print()`）转发；CI 用 `simctl launch --console-pty` 抓 stdout。

## 7. 构建 / 部署 / 验证命令

```powershell
# 一键：build:plugins → vite build → cap sync → gradlew assembleDebug → install → launch
npm run android:deploy                         # 自动选设备（优先模拟器）
npm run android:deploy -- -Device 32e93a6d     # 指定设备（npm 透传参数）

# 双端冒烟测试（真机 + 模拟器，CDP 驱动，输出 PASS/FAIL）
npm run verify:android

# iOS 静态检查（本机可跑）
npm run verify:ios

# 单独构建所有插件 / web
npm run build:plugins
npm run build
```

- 改 Java 后不需要 cap sync，直接重新 gradle（`npm run android:deploy` 会全做）。
- 改插件 TS 后：`npm run build:plugins` → 再 `npm run build`。
- 增删插件 / 改 package.json 的 `capacitor` 字段后：`npx cap sync`（android 与 ios）。

### 自动化测试体系（已搭好，别重复造）

- **Android 冒烟**：`verify-android.ps1` 每台设备 `adb forward tcp:93xx localabstract:webview_devtools_remote_<pid>`，
  `webview-eval.mjs --port <p> --file scripts/smoke-test.js`；smoke-test 在页面里 DOM 点按钮、读 `<pre>` 文本断言。
  注意：只有 debug 包支持 CDP；`webview-eval.mjs` 有 30s 超时（**不能 unref**，否则进程提前退出）。
- **iOS CI**（`.github/workflows/ios.yml`，公开仓库免费跑）：
  `npm ci` → `build:plugins` → `VITE_AUTOTEST=1 npm run build` → `cap sync ios` → `verify:ios` →
  `xcodebuild -project ios/App/App.xcodeproj -scheme App ... CODE_SIGNING_ALLOWED=NO` →
  起模拟器、`simctl launch --console-pty`、抓日志、断言 `PLUGIN_AUTOTEST_RESULT` 与 `"ok":true`、截图上传 artifact。
- **autotest**（`VITE_AUTOTEST=1` 构建后 App 自动跳 `#/plugin-test?autotest=1`）跑 4 个插件的自检并
  `console.log('PLUGIN_AUTOTEST_RESULT ' + JSON.stringify(results))`（Capacitor 会转发到原生日志）。
- GitHub 查询状态：未认证 API 有频率限制（偶尔 403）；日志需要登录。可用 workflow badge 快速看红绿：
  `https://github.com/Cinichengbuweikong/tmp_testionic/actions/workflows/ios.yml/badge.svg?branch=main`

## 8. 踩坑与关键实测结论（速查）

| # | 问题 | 结论/解法 |
| --- | --- | --- |
| 1 | JDK 17 vs Capacitor 8 要求 21 | gradlew 必须用 JBR 21（deploy 脚本已处理） |
| 2 | MIUI 拦截 `adb install` | `INSTALL_FAILED_USER_RESTRICTED` → 开发者选项开「USB 安装」 |
| 3 | 插件 `dist/` 不入库 | CI/本地必须先 `npm run build:plugins`，否则 Vite 解析不到入口 |
| 4 | Swift 包名推导 | `Package.swift` name 必须等于 CLI 推导名（见 6.3），静态检查可提前发现 |
| 5 | 插件方法不在主线程 | View/WebView 操作必须切主线程，否则 `CalledFromWrongThreadException` 崩 App |
| 6 | `setStatusBarColor` 无效 | Android 15+ 忽略；MIUI 12 也无效 → 自绘 DecorView 顶部色块（负 margin + clipToPadding=false + bringToFront） |
| 7 | MIUI 图标亮暗被覆盖 | MIUI 按背景色自动对比度，无视 `LIGHT_STATUS_BAR`（dumpsys 已确认标志写入；官方插件同环境下也无效，像素对比 0/5320） |
| 8 | MIUI WebView 不报 prefers-color-scheme | `Configuration` 是 night 但 `matchMedia(dark)=false` → 靠 set-force-dark 注入 `data-theme` |
| 9 | MIUI forceDark 反色灾难 | `setForceDarkAllowed(false)` 即可关掉；页面恢复 CSS 真实颜色 |
| 10 | 老 WebView 无安全区 env() | WebView 87 的 `env(safe-area-inset-top)=0px`（133 返回 49px）→ 用 to-edge 注入的 `--safe-area-inset-*` |
| 11 | Capacitor 默认内容在状态栏下 | `SystemBars` 插件给 DecorView 加 padding（WebView<140 或页面无 viewport-fit=cover 时）→ to-edge 插件接管 insets 监听器 |
| 12 | `onConfigurationChanged` 旋转也会触发 | 事件去重（只有 isDark/insets 真变化才派发） |
| 13 | CDP 评估挂起 | WebSocket 连接时 app 被杀；webview-eval 加 30s 超时；MIUI 会杀后台进程（`has died: cch CRE`） |
| 14 | 官方 status-bar 的 `getInfo().overlays` 误导 | 它只读 `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN`，不代表 WebView 真铺到状态栏下（实际内容在状态栏下方） |
| 15 | MIUI 手势模式假报导航栏 insets | `getSystemWindowInsetBottom`/`getStableInsetBottom` 都报 130px，但窗口实际铺满 → API29 用 `getWindowVisibleDisplayFrame()` 反推 |
| 16 | `Plugin.getConfig()` 是插件私有配置块 | 只含 `plugins.<id>` 节点，读不到全局 appName → 用 application label；`Plugin.getAppId()` 基类自带 |
| 17 | MIUI `getMode()` 返回瞬时刷新率 | 设置 90Hz 时 `getMode()/getRefreshRate()` 报 50（智能刷新率动态降档）；用户档位存 `Settings.System` 的 `user_refresh_rate`（MIUI）/ `peak_refresh_rate`（Android 11+，@hide 常量，用字符串键读） |
| 18 | iOS 26 移除 deprecated API | `UIStatusBarManager.statusBarFrame` 在 iOS 26 SDK 标记 **obsoleted（编译期就报错）**，`windowScene.interfaceOrientation` 同理 → 状态栏高度改用 `safeAreaInsets.top`（两者本就相等），旋转角改用 `UIDevice.orientation` |
| 19 | autotest 断言字段名与 API 结构脱节 | `statusBar.heightPx`（旧扁平结构）vs `statusBar.px.height`（现结构）；Android 冒烟是文本断言测不出，只有 iOS CI autotest 能抓 → 改 API 结构时同步改 autotest |
| 20 | CI 日志拿不到时怎么诊断 | 公开仓库无 token 拉不到 logs/artifact（403）；让仓库 owner 建 PAT（读权限即可）→ 下载 run logs zip 与 artifact（sim-console.log 里有完整 PLUGIN_AUTOTEST_RESULT，注意步骤日志 2000 字符截断，artifact 里的完整） |
| 21 | cutout 数据「看起来像错的」 | `boundingRects` 的 left/top/right/bottom 是**矩形四边坐标**（不是到边距离）；系统给的是**挖孔安全区矩形**（会贴屏幕边），真实圆孔在内部、看起来不贴边；横屏时坐标系换成 2400×1080（y=939~1080 即贴近底边）→ 已加 width/height/centerPx/centerCss 让数据自解释（960f52a） |

设备实测数据（真机 M2007J3SC / MIUI 12 / WebView 87）：

- WebView bounds 默认 `[0,90][1080,2180]`（模拟器 `[0,128][1080,2274]`）
- 系统深色模式：`cmd uimode night` = yes；App `Configuration` 含 night
- `matchMedia('(prefers-color-scheme: dark)')` = **false**（MIUI）；模拟器 = true
- 状态栏：蓝底→白图标、白底→深图标；`vsysui=...LIGHT_STATUS_BAR`（dark 样式时）
- to-edge enable 后：真机 `[0,0][1080,2180]`，insets `{top:33}`；模拟器 `[0,0][1080,2400]`，`{top:49,bottom:48}`

## 9. 网络与 CI

- GitHub 仓库公开：`https://github.com/Cinichengbuweikong/tmp_testionic`
- iOS workflow 每次 push 自动跑（约 8~10 分钟，免费）；本地 `git push` 用 GCM 凭据（浏览器弹窗授权）。
- 未认证 GitHub API 约 60 次/小时/出口 IP，注意限流；失败时改用 badge SVG 或让用户看网页。
- 国内网络：npm 官方源可用；Android Maven 已配国内镜像；模拟器 WebView 与系统镜像已缓存。

## 10. 当前状态 / 未完成事项

- ✅ 已推送至 `4029ff5`：capacitor-system-info **iOS CI 全绿**（iPhone 17 Pro 模拟器，13 个 API
  全部通过 autotest 断言）；Android 真机（MIUI 手势模式）+ 模拟器实测通过、冒烟全绿。
  插件 README 已含全部 API 的详细文档（功能/限制/返回值字段表/实测数据）。
- 🔨 新增 capacitor-rotation-listener（屏幕旋转监听）：**iOS CI 全绿**（acc1a45），Android 双端
  实测事件派发/解除监听正常、冒烟全绿（详见 5.6 节）。
- ✅ capacitor-system-info `getCutoutInfo()` 增强（960f52a，iOS CI 全绿）：rect 增加
  width/height，新增 centerPx/centerCss（主挖孔中心）；README 讲清「矩形坐标 vs 到边距离」
  「安全区矩形贴边 ≠ 挖孔贴边」「横屏坐标系 2400×1080」。
- ⚠️ **未提交**：`src/assets/base.css`（`--vt-c-black: #202020`；暗色 `--color-background: var(--vt-c-black)`，
  原来 debug 用的 `red` 已替换）。用户倾向保留，待其确认后提交。
- 真机（`32e93a6d`）与模拟器（`emulator-5554`）均在运行；真机为 MIUI 手势模式（无底部导航栏）。
- 用户提到「还有一件更重要的事要在别的对话做」；本仓库的插件工作可继续（做新插件 / 修 MIUI 遗留项）。
- 遗留可选任务：MIUI 状态栏图标强制控制（未攻破）；statusbar 插件在 MIUI 上颜色略偏深（约 #D83200 vs #FF5722，未深究）。
- 用户提到「还有一件更重要的事要在别的对话做」；本仓库的插件工作可继续（做新插件 / 修 MIUI 遗留项）。
- 遗留可选任务：MIUI 状态栏图标强制控制（未攻破）；statusbar 插件在 MIUI 上颜色略偏深（约 #D83200 vs #FF5722，未深究）。

## 11. 新对话快速恢复 checklist

1. 让我读 `HANDOFF.md`（本文件）+ 需要的插件 README；
2. 确认设备：`adb devices`（真机 `32e93a6d` / 模拟器 `emulator-5554`，没开就按第 3 节启动）；
3. 改完代码后 `npm run android:deploy`（装到真机/模拟器），`npm run verify:android` 跑冒烟；
4. 涉及 iOS：`npx cap sync ios` + `npm run verify:ios`，然后 `git push` 看 CI；
5. 新插件：复制 `plugins/capacitor-native-basics` 结构，按第 6 节规则命名与实现。
