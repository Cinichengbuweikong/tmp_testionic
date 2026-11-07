# 项目上下文存档（HANDOFF）

> **用途**：这份文件保存了截至 2026-11-03 本对话中掌握的所有关键信息——本机环境、项目结构、
> 已完成的 9 个自定义 Capacitor 插件、插件开发手册、验证体系、踩过的坑。
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
├─ plugins/                      # 7 个自研插件（每个都是独立 npm 包）
│  ├─ capacitor-fullscreen/         # 沉浸式全屏：隐藏状态栏+导航栏（带状态事件）
│  ├─ capacitor-keyboard-avoiding/  # 键盘避让：keyboardChanged 事件 + 可选自动避让
│  ├─ capacitor-data-channel/      # 二进制数据通道：本地 HTTP Server（127.0.0.1+token）大文件高速传输
│  ├─ capacitor-native-basics/      # 参考实现：echo / showToast / getDeviceInfo
│  ├─ capacitor-native-statusbar/   # 状态栏背景色 + 图标亮暗
│  ├─ capacitor-native-rotation/   # 屏幕方向：监听 + 主动锁定横屏/竖屏
│  ├─ capacitor-set-force-dark/     # WebView forceDark 修复 + 主题注入
│  ├─ capacitor-system-info/        # 屏幕/系统/安全区等 13 个独立只读 API
│  ├─ capacitor-webview-to-edge/    # WebView 铺满整屏 + 安全区 insets
│  └─ README.md                     # 插件集合说明 + CLI 识别规则
├─ src/
│  ├─ main.js                     # VITE_AUTOTEST=1 时自动跳 /plugin-test?autotest=1
│  ├─ router/index.js             # hash 路由：/ /about /plugin-test
│  ├─ views/PluginTestView.vue    # 「插件自检」页：9 个插件的按钮 + autotest 逻辑
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
- **2026-11-07 修复：与 webview-to-edge 一起用时色块挡住内容（Android 0.4.0）**
  - 现象：先设状态栏颜色、再 `WebViewToEdge.setEnabled(true)`（DecorView padding 清零、内容铺到
    状态栏下面）后，状态栏色块仍盖住那 128px，页面内容被挡（用户描述为"状态栏没隐藏、还盖住内容"）。
  - 修复：新增 `isStatusBarCoveredByContent()`（`insetTop > 0 && paddingTop == 0` → 内容自己画；
    `insetTop == 0` → 全屏/无状态栏）。命中时**默认不画色块**（`removeOverlay`），
    但**保留 OnGlobalLayoutListener**，这样关掉 webview-to-edge 后能自动把色块重建回来。
    新增 `setBackgroundColor({ overlayInEdgeToEdge: true })` 显式要求"盖在内容上"（用半透明色）。
    `getInfo()` 增加 `edgeToEdge` / `overlayInEdgeToEdge`。
  - 坑：第一版把监听器一起在 `removeOverlay()` 里摘掉 → 关掉 to-edge 后色块不会恢复（实测 C 场景失败），
    修正为"监听器生命周期跟随颜色，而不是跟随色块"。
  - 验证（像素量取）：模拟器 128px → **无红色（内容显示）** → 128px → 可重复；真机 K30S 90 → 无红色 → 90；
    autotest（设备/模拟器）ok:true、smoke PASS、iOS CI 绿（iOS 侧逻辑不变）。
  - iOS 说明：Capacitor 在 iOS 默认就是内容铺满（contentInsetAdjustmentBehavior=.never），
    色块=给状态栏上色属预期，无需此逻辑（README 已注明）。
- **2026-11-07 修复：旋转后色块只有一半高（Android 0.3.0）**
  - 现象（模拟器 Pixel 6 / Android 16，竖屏状态栏 128px、横屏 63px）：竖屏正常 → 横屏正常 →
    **转回竖屏时色块 rows 65..127（63px）**，只盖住下半截。
  - 根因：`handleOnConfigurationChanged` 触发时 DecorView 还没重新布局，
    `ViewCompat.getRootWindowInsets()` 返回**旧方向** Insets（63px），而 `topMargin=-paddingTop`
    用的是**新**方向（128px）→ 色块落在 128-63=65..128。
  - 修复：几何计算挪到 `ViewTreeObserver.OnGlobalLayoutListener`（布局后 padding/Insets 已更新），
    加"值未变不 setLayoutParams"去重防死循环；配置回调仍保留用于重新套用颜色/样式。
  - 验证（修复后）：模拟器 128/63/**128**/128（两轮）+ 横屏改色再转回（绿 63→128）✓；
    真机 K30S 90/90/90（该机型横竖屏同高，无回归）✓。
  - **iOS 无需修**：色块是 Auto Layout（`bottomAnchor == safeAreaLayoutGuide.topAnchor`），
    旋转由安全区自动驱动，没有缓存高度的问题。
  - 复现/验证的 adb 命令与量像素脚本见插件 README「旋转 / 横竖屏切换」一节。

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

### 5.6 capacitor-native-rotation（屏幕方向：监听 + 主动控制）

- Android 包 `com.testionic.rotation`；iOS 包 `CapacitorNativeRotation`；jsName `Rotation`；
  npm 包名 `capacitor-native-rotation`（**0.2.0 从 capacitor-rotation-listener 改名**）。
- API：
  - 监听：`getRotation()` → `RotationState { orientation, rotationDegrees, isPortrait, isLandscape }`；
    `addListener('rotationChanged', cb)`（返回 handle，`handle.remove()` 解除）；`removeAllListeners()`（桥内置）。
  - 控制：`lock({ orientation: 'landscape'|'portrait'|'auto' })`、`unlock()`、`getLock()`
    → `OrientationLockState { orientation, locked }`。**视频「全屏播放」= lock(landscape) + Fullscreen.enable()**。
- **Android**：事件源 `Plugin.handleOnConfigurationChanged`（要求 MainActivity 的 `android:configChanges`
  含 orientation|screenSize，Capacitor 模板默认已含）；方向锁定用 `Activity.setRequestedOrientation`
  （landscape→SENSOR_LANDSCAPE 支持左右两方向 / portrait→SENSOR_PORTRAIT / auto→UNSPECIFIED），
  锁定引起的旋转同样走配置变化 → 自动派发 rotationChanged。
- **iOS**：`UIDevice.orientationDidChangeNotification`（load beginGenerating、deinit 注销）；
  orientation 用 WebView 宽高比、角度由 UIDevice 映射；锁定改
  `CAPBridgeViewController.supportedOrientations`（public 可写）+ iOS16 的
  `setNeedsUpdateOfSupportedInterfaceOrientations()` + `requestGeometryUpdate()`，
  iOS15 用 `UIDevice` KVC + `attemptRotationToDeviceOrientation()`；
  解锁时把 supportedOrientations 还原成进入前记录值。不用 iOS 26 已 obsoleted 的 `interfaceOrientation`。
- **Web**：`screen.orientation` change 派发事件；锁定用 `screen.orientation.lock()/unlock()`
  （浏览器要求页面全屏且支持该 API，否则 reject）。
- 实测（2026-11-04 真机 MIUI 12）：`lock(landscape)` → 返回 locked:true、屏幕真实横屏
  （截图 2400×1080、getRotation→landscape/90）；`unlock()` → 回竖屏 portrait/0。
  旋转事件实测（模拟器 + 真机）：`landscape/90` ↔ `portrait/0`，解除监听后不再触发。
- 坑：`JSObject.getInt(String,int)` 不存在（org.json 只有单参），要用 `optInt(name, default)`。

### 5.6b capacitor-native-statusbar（0.2.0 修复：暗色模式白色失效）

- **现象**：MIUI 12 暗色模式下 `setBackgroundColor('#FFFFFF')` 后 `getInfo()` 返回 #FFFFFFFF、
  `usingOverlay:true`，但状态栏实际像素是 **rgb(27,27,27) 近黑**；橙色 #FF5722 也被压成 #D83200。
- **根因**：Android Q+ 的 **force dark**（暗色模式对 App 内 View 智能反色），MIUI 12 对第三方 App 生效；
  色块是普通 View，被系统反色。与 WebView 的 forceDark 是两回事（作用在原生 View 树）。
- **修复**：给自绘色块单独关闭 force dark（`overlayView.setForceDarkAllowed(false)`，API 29+），
  不动全局主题/窗口。**不要**用 `android:forceDarkAllowed="false"` 关整个 App。
- **实测**：修复后 白色→rgb(228,232,227)（近白，取样含图标）、橙色→**rgb(255,87,34) 精确 #FF5722** ✅

### 5.7 capacitor-fullscreen（沉浸式全屏）

- Android 包 `com.testionic.fullscreen`；iOS 包 `CapacitorFullscreen`；jsName `Fullscreen`
- API：`enable / disable / setFullscreen({enabled}) / toggle / isFullscreen()`（返回
  `FullscreenState { fullscreen, statusBarHidden, navigationBarHidden }`）；
  `addListener('fullscreenChanged')`、`handle.remove()` 解除（桥内置）。
- **与 webview-to-edge 的区别**：to-edge 系统栏仍可见（内容延伸下去）；本插件系统栏**完全隐藏**。
- **Android**：`WindowInsetsControllerCompat.hide(systemBars())` +
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（immersive sticky：边缘滑动可临时唤出系统栏）。
  系统栏隐藏 → insets=0 → WebView padding 自动归零铺满，插件**不需要手动改布局**；
  退出后 padding 自动还原。与 webview-to-edge 的 insets 监听兼容。
- **iOS**：状态栏走 Capacitor 公开 API `bridge.statusBarVisible`；Home Indicator 无法直接隐藏，
  只能 `prefersHomeIndicatorAutoHidden` —— 借内置 `SystemBars` 插件的 `hide:`/`show:`（@objc
  运行时调用 + responds 检查，拿不到插件时自动降级为只隐藏状态栏）。
- **Web**：浏览器 Fullscreen API（需用户手势），`fullscreenchange` 事件同构派发。
- 实测（2026-11-03）：真机 `enable()` 后截图——状态栏与导航栏完全消失、内容从屏幕顶边渲染；
  `disable()` 后完整恢复；冒烟测试双端全 PASS（进入/退出/读取/监听/取消）。
- 坑：`androidx.core:core` 在 `capacitor-android` 里是 `implementation` 依赖**不传递**，
  插件必须显式声明 `implementation "androidx.core:core:$androidxCoreVersion"`。

### 5.8 capacitor-keyboard-avoiding（键盘避让）

- Android 包 `com.testionic.keyboardavoiding`；iOS 包 `CapacitorKeyboardAvoiding`；
  jsName `KeyboardAvoiding`；npm 包名 `capacitor-keyboard-avoiding`。
- API：`enable / disable / setEnabled({enabled}) / isEnabled() / getState()` →
  `KeyboardState { visible, height(css), heightPx, viewportHeight(css), viewportHeightPx,
  autoAvoid, animationDuration }`；`addListener('keyboardChanged')`、`handle.remove()` 解除。
- **核心设计**：
  - 纯监听（默认）：只派发事件，不改布局；
  - 自动避让（enable）：Android **补差**（不盲加）、iOS **contentInset**（WKWebView 默认不避让）。
- **Android 实测关键结论**：真机 MIUI 12 键盘弹出后 WebView 2310px→1382px、innerHeight 840→503css
  （键盘 928px/337css）——**系统已自动避让**，所以 autoAvoid 必须"补差"：
  用 `getRootWindowInsets().getInsets(Type.ime())` 读高度（**不注册** listener，避免与
  webview-to-edge/SystemBars 抢），用 WebView `bottomMargin` 补齐 deficit（反推原始底部计算，幂等不抖动）。
  键盘状态靠 `ViewTreeObserver.OnGlobalLayoutListener` 触发检测。
- **iOS**：`keyboardWillChangeFrameNotification` / `keyboardWillHideNotification`，
  键盘高度 = WebView 底部与 keyboardFrameEnd 顶边的重叠；autoAvoid 用
  `scrollView.contentInset.bottom`（关闭时还原基线 inset）；iOS point = CSS px。
- **Web**：`visualViewport` 差值计算键盘高度（<80px 视为浏览器 UI 折叠），同构派发事件。
- 实测（2026-11-04 真机）：键盘弹出事件 `{visible:true, height:337.45css/928px, viewportHeight:502.55css}`；
  键盘已弹出时 enable → innerHeight 保持 503（**无双重缩小**）✅；关闭键盘事件
  `{visible:false, height:0, viewportHeight:840}`、事件总数 2；disable 恢复 autoAvoid:false。
- 坑：Android 模拟器默认"硬件键盘"模式下软键盘不弹（需 `settings put secure
  show_ime_with_hard_keyboard 1`，且 AOSP 模拟器仍可能不稳定）→ 键盘相关验证以**真机**为准。
- **重要（用户实测疑问）**：真机上 enable/disable 看起来"都避让了"是**正常现象**——
  ① Capacitor 模板 `windowSoftInputMode=adjustResize` 让**系统自己缩窗口**；
  ② `webview-to-edge` 又会把 IME 高度设成 DecorView 底部 padding。
  本插件的 disable 只是"不再额外加"，撤销不了这两层；要彻底关掉得改宿主
  `adjustNothing` 并同步调整其它插件（不属于本插件职责）。README 已写明，
  并给出验证插件是否工作的方法（看 autoAvoid 字段 / 事件的 height+viewportHeight）。

### 5.9 capacitor-data-channel（原生↔WebView 二进制数据通道）

- 背景：Capacitor 桥是 JSON 序列化，复杂类型必须 base64（官方注释自证：
  "FormData, Blob, ArrayBuffer ... only directly supported on web"）→ 大文件传输代价极高。
- 方案：原生侧启动**只监听 127.0.0.1 的 HTTP Server**（随机端口 + 32 字节 Bearer token），
  WebView 用 `fetch` / `<video>` / `<img>` 直接收发原始二进制，**完全绕开桥**。
- 两端各自手写 HTTP 实现（零第三方依赖）：Android `DataChannelServer.java`（ServerSocket+线程池）、
  iOS `DataChannelServer.swift`（NWListener + 状态机）；**协议完全一致**：
  `/health`、`PUT|POST /upload`（流式、`append=1` 续传）、`GET /download`（支持 Range→206）、
  `OPTIONS` 预检（**2026-11-07 起只保留这三个路由**，见下）。
- **2026-11-07 职责收敛**（用户要求："各自只负责各自的部分"）：
  - **删除了 `checkAllFilesAccess` / `requestAllFilesAccess`**（权限 API 归 `capacitor-native-files`）；
  - **删除了 `/info`、`/list`、`DELETE /file` 路由**（文件管理归 native-files）；
  - 测试页删掉「检查/申请存储权限」「列出文件」按钮；autotest 断言这三个路由**已 404**；
  - `startHttp()` 失败时的错误信息改为提示"用 capacitor-native-files 授权"。
- **2026-11-07 OTP 鉴权改造 + 双 server 分离**（用户主导设计，核心安全升级）：
  - **两个独立 server**：`startHttp/stopHttp`（HTTP）与 `startWebSocket/stopWebSocket`（WS），
    各自随机端口、各自 OTP 表、各自连接限流；状态分开读：`getHttpInfo()` / `getWsInfo()`
    （停止时也返回结构体，`running: false`）；
  - **OTP 替代长期 token**：OTP 通过 **Capacitor 桥**下发（`createHttpOtp` / `createWsOtp`），
    桥是进程内 `postMessage`，**抓包看不到生成过程**；抓包只能看到已作废的 OTP；
  - **OTP 规则**：一次性（默认）/ reusable（**仅 scope=read**，媒体用）；**写不允许 reusable**；
    **无 TTL**（= server 生命周期）；HTTP OTP 的 `path` **必填且精确匹配**；WS OTP 只一次性；
    OTP 固定 **43 字符**（32 字节随机 base64url）；上限 256；HTTP 校验失败统一 404；
  - **必须 revoke**（README 有加粗警告）：reusable OTP 用完不 revoke → server 运行期间永久有效；
    提供 `revokeOtp` / `revokeAllOtps({server})`；测试页/autotest 覆盖 revoke 前后行为；
  - **传递方式**：HTTP `Authorization: OTP <otp>`（普通）或 `?otp=`（媒体 URL）；
  - **WS 逐条 OTP（用户要求"每条消息一个新 OTP"）**：WebSocket 消息**没有头**，所以自定义协议
    `[OTP(43 字符 ASCII)][0x00][payload 原始二进制]`；按**第一个 0x00** 分割（OTP 是 base64url
    永不含 0x00，payload 可含 0x00）；错误消息为 `[0x00][错误文本 UTF-8]`；
    - **Client→Server**：每条消息前 `createWsOtp()`；Server 校验失败 → 丢弃 + 回错误消息；
    - **Server→Client**：Server 每条消息生成一次性 nonce 放进消息前缀；JS 必须调
      **`validateAndConsumeOTP`** 验证并消费（否则表会堆积，满 256 时回 `too many pending nonces`）；
    - 为什么反向验证：若 Server 通过桥"提前通知" OTP，桥事件与 WS 消息是两条通道、顺序无保证（竞态）；
    - **helper（TS 导出）**：`encodeWsMessage(payload)` / `decodeWsMessage(data)`，内部自动
      `createWsOtp` / `validateAndConsumeOTP`；`core.ts` 单独放 `registerPlugin` 避免循环依赖；
  - **两级连接限流**：未认证 4（防扫描/DoS）/ 已认证 16（`maxConnections` 可调），HTTP/WS 分开计数；
  - **威胁模型**（README 有表）：防普通网页/非 root App/重放；**不防 root 设备与 WebView 被注入**；
  - **实测（真机 + autotest 全绿）**：重放写/读 OTP → 404；OTP 绑 A 请求 B → 404；read OTP 写 → 404；
    写+reusable → reject；穿越 path → reject；媒体 reusable 两次 200 → revoke 后 404；
    WS 握手重放 → 连接被拒；**WS 消息无 OTP → 错误消息**；**WS 消息重放 → 第二次错误消息**；
    **validateAndConsumeOTP 首次 true / 再次 false**；payload 中的 0x00 正确保留；
    6 个沉默连接 → pending 停在 4；stop 后 getHttpInfo/getWsInfo 各自 running:false；
  - 坑：Java `handleRequest` 的 catch 分支漏 `return`（编译报"缺少返回语句"）；
    `JSObject.put(key, null)` 会跳过 → 用 `org.json.JSONObject.NULL`；Swift 字典赋 nil 会**移除键** →
    用 `NSNull()`；DELETE 请求的 CORS 预检 `Allow-Methods` 不含 DELETE（路由已删，测试改用 GET）；
    `verify-ios.mjs` 原来只从 `index.ts` 找 `registerPlugin`，拆出 `core.ts` 后要两个文件都看。
- **2026-11-07 Capacitor 原生调用调度实测 + 批量 OTP**（用户要求先查清调度模型）：
  - **Android**：`Bridge` 里 `HandlerThread("CapacitorPlugins")` → `taskHandler.post()` 逐条执行
    → **所有插件调用串行在单线程**；响应 `webView.post(() -> evaluateJavascript)` → **主线程**；
  - **iOS**：`CapacitorBridge.dispatchQueue = DispatchQueue(label: "bridge")`（串行队列）→ 插件调用串行；
    响应 `DispatchQueue.main.async { evaluateJavaScript }` → **主线程**；
  - 因此：**JS 并发调原生 = 原生排队串行执行**，但往返延迟可流水（实测 100 次顺序 198ms=1.98ms/次，
    100 次并发 57ms=**0.57ms/次**，加速 3.44×）；
  - 实测性能（真机 Redmi K30S / Android 10，`/proc` 精确 CPU）：HTTP 上传 93MB/s、下载 322~333MB/s
    （峰值 PSS +46MB）；WS 1KB 4.76ms/条、300KB 15.89ms/条、1.5MB 37.4ms/条、流水线 49.3MB/s；
    峰值 PSS 均回落到基线（无泄漏）；400 次 OTP 调用期间 rAF 79→57fps（有可感知掉帧）；
  - **批量 OTP（0.4.0）**：`createWsOtps({count})` / `createHttpOtps({count,...})` /
    `validateAndConsumeOtps({otps})`；TS helper：`createWsOtpPool(n)`、`encodeWsMessageWithOtp`（同步）、
    `decodeWsMessagesBatch`（先验证后交付）。上限 256（活跃 + 请求数）。
    实测：300KB 顺序往返 **15.89 → 10.33ms/条（-35%）**，吞吐 63 → 97 条/s；autotest 覆盖
    "8 个不重复 / 批量拼包全部 ok / 批量 nonce 再验证全 false / HTTP 批量 3 个可用"；
  - 坑：`webview-eval.mjs` 在 CDP `ws.close()` 后 node 偶发不退出（会让采样/CI 卡死）→ 结尾加
    `process.exit(0)`；`.perf/` 基准脚本里"流水线 + 批量校验"版本有死锁（未修复，改用顺序池版本取证）。
- **2026-11-07 data-channel 上传协议加固（断点续传 + 结构化错误）**：
  - 旧 `append=1` 的问题：块传一半断开会把**半块字节留在目标文件**里，后续 resume 静默拼错；
    且中断时 `copyExactly` 抛异常直接进 catch（**不返回错误体、不打日志**）；
  - 新协议：分块写 `X.part`，`PUT ...&offset=<n>&total=<T>`（**append 前校验 .part 长度 == n**），
    `GET ...&status=1` 查进度，`POST ...&finalize=1&total=<T>[&sha256=]` 校验后原子 rename；
    `append=1` **废弃** → 400 `E_APPEND_DEPRECATED`；`/upload` 的所有方法都要求 write OTP；
  - 错误统一 `{error, code, size?}`：`E_INVALID_PATH/E_BAD_REQUEST/E_LENGTH_REQUIRED/
    E_PAYLOAD_TOO_LARGE/E_OFFSET_MISMATCH/E_NO_PART/E_SIZE_MISMATCH/E_HASH_MISMATCH/
    E_FINALIZE/E_IO/E_APPEND_DEPRECATED`；409 系列带服务端实际 `size`；
  - **中断处理**：对端断开（EOF/reset）只记日志不发响应（连接已不可用），`.part` 保留可续传；
    服务端写盘失败返回 500 `E_IO`；`handleRequest` 的 catch 现在会 `Log.e`；
  - **iOS 修掉静默失败**：`completeUpload` 的 `try?` 改为 `do/catch`（rename 失败返回 500 且不落地）；
    `writer.write(Data)`（非 throwing，失败抛 ObjC 异常）改为 `try writer.write(contentsOf:)`；
  - 实测（真机 autotest 全绿）：offset 错位 → 409 + size=204800；status.partSize 一致；
    finalize 总长不符 → 409 且目标 404；sha256 正确 200 / 错误 409 `E_HASH_MISMATCH`；
    `append=1` → 400；XHR 中断 → 目标不存在；手工 20MB 续传（8+8 → status 16MB → 续 4MB → finalize）
    324ms 下载 20MB ✓；smoke PASS；
  - 文档：README 新增「断点续传上传（offset + finalize）」+ 错误码链接；新增 **EXAMPLES.md**
    （上传/续传/下载/媒体/WS/错误处理/性能调优/陷阱）；INTRO 同步。
  - **坑（重要）**：
    - **autotest 用例必须幂等**：续传用例一开始写死 `selftest/resumable.bin`，上一轮 finalize 留下的
      最终文件让"finalize 前目标不存在"断言变成 200 → 改为每次 `Math.random()` 后缀的唯一路径；
    - **中断用例必须有兜底**：XHR abort 在部分平台可能不回调 `onabort/onerror/onload` → 整个 autotest
      会挂住、CI 报 "marker not found"；加 3s 兜底 resolve；
    - **CI 不能用固定 sleep 等 autotest**：iOS workflow 原来 `sleep 45`，用例变多后超时 → 改为轮询
      `PLUGIN_AUTOTEST_RESULT`（最长 180s，找到即继续）；
    - 排查技巧：CI 日志需要鉴权时，job summary（`Publish autotest result to job summary`）里的
      `::error::` 注解会出现在 **job 页面 HTML** 里，可以直接抓 HTML 判断是 "marker not found"
      还是 "reported a failure"。
- **2026-11-07 data-channel 文档定稿**：README 新增「Capacitor 调用调度模型」「安全讨论与最终结论」
  （token→OTP 动机、OTP 与 token 的等价/差异、保留逐条 OTP 的取舍、不做传输加密的 5 条推理、
  承诺/不承诺清单、提高门槛的优先项）「性能建议」；新增 [INTRO.md](plugins/capacitor-data-channel/INTRO.md)
  作为插件介绍（定位/对比表/30 秒上手/适合不适合/平台验证矩阵）。
- **2026-11-07 平台限制实测修正（重要，之前文档是错的）**：
  - 真机 Android 10（API 29）+ targetSdk 36 + `requestLegacyExternalStorage="true"`：
    `privateFlags` 含 `PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE` → **legacy 生效**；
  - 关键：`adb shell pm grant WRITE_EXTERNAL_STORAGE` **不会触发 mount namespace remount**
    （App 仍挂 `/mnt/runtime/default/emulated`，gid=1015, mask=6 → 读写都 `EACCES`）；
  - **走系统权限弹窗授权后**：`/storage/emulated` 挂载变 `gid=9997, mask=7`（write 视图）→
    **读 `/sdcard/Download`、写 `/sdcard/Download/...` 全部成功**（已实测）；
  - 所以：**Android 10 及以下可以读写公共目录**（需真实授权），之前"targetSdk>=30 时 scoped storage
    强制"的结论是**错误**的；Android 11+ 才需要 MANAGE_EXTERNAL_STORAGE；
  - App 专属外部目录（默认）**任何版本都免权限**。
- 宿主配置（必需，否则 WebView 拒绝访问明文回环）：
  - Android：`res/xml/network_security_config.xml` 只对 127.0.0.1/localhost 放行 cleartext +
    manifest `android:networkSecurityConfig`（另需 `requestLegacyExternalStorage="true"` 才能用公共目录）；
  - iOS：Info.plist `NSAppTransportSecurity → NSAllowsLocalNetworking = true`。
- 安全设计：只绑回环（iOS 用 `requiredInterfaceType = .loopback` + 连接来源校验）、随机端口、
  Origin 白名单（非法 Origin **静默断连**）、OTP 校验失败统一 **404**、两级连接限流、
  路径 canonical/软链校验、请求大小/头/超时限制、上传 `.part` 原子写、日志不记 OTP。
- **内存实测（真机，App 进程 RSS 峰值增量）**——README 已收录为示例：
  | 方式 | 105~110MB 文件 | 速度 | RSS 增量 |
  |---|---|---|---|
  | 分块 8MB×13 复用缓冲 + append（**首选**） | 920ms | 109 MB/s | +42.8MB |
  | File 直传（仅限磁盘文件场景） | 806ms | 131 MB/s | **+4.2MB** |
  | 一次性 `Uint8Array(100MB)` | 912ms | 110 MB/s | +275.3MB |
  | 整体生成后包装 `new File([buf])` | 698ms | 143 MB/s | 上传阶段 +0.6MB（生成峰值仍在） |
  → 真实场景主要是两个方向：**读**（用户选文件 → `Blob.slice` 分块加载进 WebView，
  别用 `arrayBuffer()` 一次性读）和**写**（WebView 生成数据 → **分块 + 复用 buffer** 写回磁盘，
  内存恒定 + `append` 断点续传）；File 直传只适用于"选文件后原样转发"这个少见场景；
  server 自身空闲开销仅 +0.5MB RSS / 0.13% CPU。
- 安全思路结论（用户提议"故意返回迷惑数据"）：**不做伪装**（拦不住真攻击者，还破坏 Range/CORS/
  调试），改为"默认拒绝 + 最小暴露面"（404 隐藏 + 静默断连），已实现并实测。
- 坑：JavaDoc 注释里手滑写 `*/` 导致注释提前结束、`android.util.Log` 没有 `debug()`
  （只有 `Log.d`）、NWConnection/NWListener 的队列必须是 **serial**（不能用 concurrent）。
- **2026-11-05 增强**（rootDir + WebSocket）：
  - `rootDir` 三种写法：不传 = App 专属外部目录 `/sdcard/Android/data/<pkg>/files/dataChannel`
    （无需权限）/ 相对路径 = App 私有目录 / 绝对路径 = 任意位置（受平台限制）；
  - **WebSocket server**：同端口 `/ws`（RFC6455 子集：帧解析/mask/分片重组/ping-pong/close，默认 echo），
    `ChannelInfo` 增加 `wsUrl`/`wsClients`/`webSocketEnabled`；`start({ wsEcho:false })` 关闭 echo。
  - **WS 实测**：1MB 往返 45ms、10MB 往返 181ms（55 MB/s）、64KB 帧每帧 1.7ms（588fps）、空闲 CPU 0%。
  - 坑：Swift 里 internal 方法参数用 private 类型（`HTTPConnection`）→ 必须 `fileprivate`；
    Swift 单 target 不能混合 Swift 与 C/C++（见 C/C++ 调研文档）。
  - **iOS 连接挂起根因（已修）**：`accept()` 里创建的 `HTTPConnection` 是局部变量，`start()` 后
    立即被释放 → `[weak self]` 回调全落空，连接 ready 但永不处理请求。修复：server 用
    `httpHandlers` 数组强引用，`finish()` 调 `server.unregisterHandler(self)`。

### 5.10 capacitor-foreground-state（前后台状态，原名 capacitor-app-state）

- `getState()` → `{ isActive, state, changedAt, lastBackgroundAt, backgroundDuration }`；
  `stateChanged` 事件；`handle.remove()` 取消单个、`removeAllListeners()` 取消全部。
- **2026-11-07 改名**：`capacitor-app-state` → **`capacitor-foreground-state`**（目录/包名/iOS SPM 名
  `CapacitorForegroundState`/Android 包 `com.testionic.foregroundstate`/jsName `ForegroundState` 全部同步）。
- Android 用 `handleOnResume`/`handleOnPause`（只有 active/background）；
  iOS 用 4 条 UIApplication 通知（active/inactive/background，inactive 是过渡态）。
- 真机实测：HOME 切后台 → 事件 `background`；回前台 → 事件 `active` 且 `backgroundDuration=3232ms` ✓。
- 用途：配合 data-channel 在切后台时暂停/提示（iOS 后台 socket 会被挂起）。

### 5.11 capacitor-native-files（本地文件系统）

- 11 个 API：`pickFiles`（**多选**）/ `pickFolders`（**单选**，Android SAF 系统限制）/
  `readFile`（base64，offset/length，单次 16MB 上限）/ `writeFile`（create|append|overwrite）/
  `deleteFile` / `listFolder` / `createFolder` / `deleteFolder` / `renameFolder` / `getInfo` /
  `copyToAppStorage`（复制到 data-channel 默认根目录，返回 relativePath）。
- **URI 语义**：Android 是 `content://`（SAF，无需存储权限）；iOS 是 `file://`（security-scoped，
  附 bookmark）。**不是文件系统路径**——要路径就给 data-channel 用 `copyToAppStorage`。
- 多选支持结论：文件多选两端都支持；**文件夹多选 Android 系统不支持**（`ACTION_OPEN_DOCUMENT_TREE`
  只能单选），iOS 虽支持但为跨平台一致也做单选。
- 真机实测：11 个方法全部注册；`getInfo` 对无效 URI 返回 `exists:false`（file:// 与 content:// 均正确）。
  文件/文件夹选择器是系统 UI，需手动验证（未自动化）。
- 与 data-channel 配合流程：`pickFiles` → `copyToAppStorage` → data-channel 上传。

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

- ✅ capacitor-system-info（13 个独立只读 API）：iOS CI 全绿；Android 双端实测通过。
  `getCutoutInfo()` 已增强（width/height + centerPx/centerCss，960f52a）。
- ✅ capacitor-fullscreen（沉浸式全屏）：iOS CI 全绿（59a9e64）；Android 双端截图验证通过。
- ✅ capacitor-native-rotation **0.2.0**（原 capacitor-rotation-listener 改名）：新增
  `lock/unlock/getLock` 主动方向控制（视频全屏播放场景）。真机实测：lock(landscape) → 横屏
  （截图 2400×1080、rotation landscape/90）；unlock → 竖屏 portrait/0。**iOS CI 全绿**（65eac4c）。
  坑：iOS 的 `requestGeometryUpdate` 是 **UIWindowScene** 的方法（走 `view.window?.windowScene`），
  不是 UIViewController 的。
- ✅ capacitor-native-statusbar **0.2.0**：修复暗色模式 force dark 导致白色失效/橙色偏暗——
  给自绘色块单独 `setForceDarkAllowed(false)`；实测 白色→rgb(228,232,227)、橙色→精确 #FF5722。
- ✅ **capacitor-data-channel**（二进制数据通道）：本地 HTTP + WebSocket Server 大文件/实时流传输；
  真机实测 File 上传 105.8MB **806ms/131MB/s、内存 +4.2MB**；WS 10MB 往返 181ms/55MB/s、
  64KB 帧 1.7ms/588fps、空闲 CPU 0%；rootDir 支持默认/相对/绝对路径（Android 10 公共目录受
  scoped storage 限制，已验证并给出明确错误）；Range/404/CORS 全通过；双端冒烟 PASS。
  **iOS CI 待定**：Swift 编译错误（fileprivate）已修（198c1b6），运行验证待测（详见 5.9 节）。
- ✅ **capacitor-foreground-state**（前后台状态，原 `capacitor-app-state`，2026-11-07 改名）：真机实测
  HOME 切后台/回前台事件与 `backgroundDuration=3232ms` 全通过；iOS CI 待验证（详见 5.10 节）。
- ✅ **capacitor-native-files**（本地文件系统，新增）：11 个 API；真机验证方法注册与
  `getInfo` 无效 URI 行为；选择器为系统 UI 需手动验证；iOS CI 待验证（详见 5.11 节）。
- 📄 **C/C++ 插件调研文档**：`docs/capacitor-cpp-bridge-research.md`（Android NDK+CMake / iOS SPM
  双 target / 零拷贝 / 坑清单 / 推荐实现顺序），后续按文档实现 `capacitor-cpp-bridge` 模板。
- ✅ capacitor-keyboard-avoiding（键盘避让）：真机实测键盘事件与补差逻辑全通过
  （键盘弹出 height 337.45css/928px、enable 无双重缩小、关闭事件正常）；双端冒烟 PASS；
  **iOS CI 全绿**（e4a1fe0 attempt 2；attempt 1 是模拟器 flaky——App 未启动、console 为空，
  重跑即过，遇到了就重跑）。
- ⚠️ **未提交**：`src/assets/base.css`（`--vt-c-black: #202020`；暗色 `--color-background: var(--vt-c-black)`，
  原来 debug 用的 `red` 已替换）。用户倾向保留，待其确认后提交。
- 真机（`32e93a6d`）为 MIUI 手势模式（无底部导航栏）；模拟器（`emulator-5554`）按需启动
  （2026-11-05 晚被用户关闭，需要时重新启动）。
- 遗留可选任务：MIUI 状态栏图标强制控制（未攻破）；iOS 上 data-channel 的 WebView fetch 是否
  受 ATS/混合内容限制（已加原生 self-test 区分，待 CI 结果确认）。
- 🔴 **用户反馈（2026-11-05 晚）**：简单查看后认为"还是有问题的，各种地方都要改一下"，
  但**未列出具体问题**，且当天已疲劳暂停。**下次恢复第一件事：请用户指出具体问题点，
  逐条确认后再改**（不要自行猜测大改）。可能涉及：data-channel 的 rootDir/WS 文档与行为、
  foreground-state（原 app-state）/ native-files 的 API 细节与 README、测试页/冒烟脚本的断言等。

> **2026-11-07 进展**：data-channel 已按用户要求完成 6 轮加固（职责收敛 → OTP 鉴权 + 双 server
> → 逐条 OTP + 协议 → 批量 OTP → 性能基准 + 安全讨论 → 可续传上传 + 结构化错误），
> 真机/模拟器/iOS CI 全绿；`capacitor-app-state` 已改名 `capacitor-foreground-state`。

## 11. 新对话快速恢复 checklist

1. 让我读 `HANDOFF.md`（本文件）+ 需要的插件 README；
2. 确认设备：`adb devices`（真机 `32e93a6d` / 模拟器 `emulator-5554`，没开就按第 3 节启动）；
3. 改完代码后 `npm run android:deploy`（装到真机/模拟器），`npm run verify:android` 跑冒烟；
4. 涉及 iOS：`npx cap sync ios` + `npm run verify:ios`，然后 `git push` 看 CI；
5. 新插件：复制 `plugins/capacitor-native-basics` 结构，按第 6 节规则命名与实现。
