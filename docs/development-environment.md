# 开发环境说明（本机实测）

> 本文档记录 2026-11-02 在当前 Windows 机器上实际探测到的环境，以及跑通 Capacitor 8 + 自定义插件的完整链路。
> 换机器 / 重装环境时，按这里逐项核对即可。

## 1. 硬件与系统

| 项 | 值 |
| --- | --- |
| 系统 | Windows 10（10.0.19045） |
| 磁盘 | C: 约 57 GB 可用；D: 约 34 GB 可用 |
| 项目路径 | `D:\projs\testionic` |

## 2. 工具链版本

| 工具 | 版本 / 路径 | 备注 |
| --- | --- | --- |
| Node.js | v24.17.0（`C:\Program Files\nodejs`） | Capacitor 8 要求 Node ≥ 22 ✅ |
| npm | 11.13.0 | 只装了 npm，没有 pnpm / yarn |
| npm registry | `https://registry.npmjs.org/` | 默认官方源，装包正常 |
| JDK（JAVA_HOME） | Temurin 17.0.19 | ⚠️ 只有 17，**不满足** Capacitor 8 的 JDK 21 要求 |
| JDK 21 | Android Studio 自带 JBR 21.0.10：`C:\Program Files\Android\Android Studio\jbr` | 命令行构建 Android 时必须用它 |
| Android Studio | `C:\Program Files\Android\Android Studio`（Otter 代） | 满足 Capacitor 8 对 AS 版本的要求 |
| Android SDK | `C:\Users\cinic\AppData\Local\Android\Sdk` | `ANDROID_HOME` 已设置 |
| SDK Platform | android-36、android-36.1 | compileSdk/targetSdk = 36 ✅ |
| Build-Tools | 35.0.0 / 36.0.0 / 36.1.0 / 37.0.0 | |
| adb | 37.0.0 | |
| Gradle | wrapper 8.14.3（腾讯云镜像下载） | AGP 8.13.0 ✅ |
| Git | 2.45.2 | 项目当前**不是** git 仓库 |
| Python | 3.11（`C:\python311`） | 与本项目无关 |

### ⚠️ 最大的坑：JDK 版本

`android/app/capacitor.build.gradle` 里写死了：

```gradle
sourceCompatibility JavaVersion.VERSION_21
targetCompatibility JavaVersion.VERSION_21
```

而系统 `JAVA_HOME` 指向 JDK 17，所以**直接在命令行跑 `gradlew` 会失败**。
两种解决办法（二选一）：

1. 用脚本：`npm run android:deploy`（内部会把 `JAVA_HOME` 临时指向 Android Studio 的 JBR 21）；
2. 手动：`$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'` 后再跑 gradlew。

也可以用 Android Studio 打开 `android/` 目录构建，AS 默认用自己的 JBR 21，不会有这个问题。

## 3. 设备

| 设备 | 信息 | 状态 |
| --- | --- | --- |
| 真机 | 小米 M2007J3SC（Redmi K30S Ultra），Android 10（API 29 / MIUI V12），arm64-v8a | ✅ 可用（需在「开发者选项 → USB 安装」放行，安装时手机上点确认） |
| 模拟器 | AVD `Pixel_6`（android-36 default x86_64） | ✅ 可用。无窗口启动：`emulator -avd Pixel_6 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect` |

模拟器首次开机较慢（本次约 1 分钟），且会弹 “System UI isn't responding”，点 Wait 即可。
软件渲染下界面会卡顿，但足以做功能验证与截图。

启动模拟器后：

```powershell
adb devices                                  # 应看到 emulator-5554
adb -s emulator-5554 shell screencap -p /sdcard/s.png
adb -s emulator-5554 pull /sdcard/s.png .
adb -s emulator-5554 shell input tap <x> <y> # 点击
```

## 4. 项目现状

- Capacitor **8.6.0**（core / cli / android），官方插件 `@capacitor/status-bar@8.0.4`
- 前端：Vue **2.7** + vue-router 3（hash 模式）+ Vite **3** + `@vitejs/plugin-legacy`
- `capacitor.config.json`：appId `com.myapp.testionic`，webDir `dist`
- 平台：**Android + iOS**（Capacitor 8 的 iOS 模板默认用 SPM）。iOS 无法在本机编译，方案见 [ios-without-mac.md](./ios-without-mac.md)
- `android/build.gradle` 已配置国内 Maven 镜像（腾讯云 / 阿里云 / 华为云 / 网易），换机器时网络可能不用再折腾
- 已有一个自己签名的 release APK：`android/app/release/app-release.apk`（签名文件在 `android/key/key`，密码未知）

## 5. 已验证的完整链路

```
写插件（TS + Java） → 插件 npm run build
   → 应用 npm install ./plugins/xxx（npm 建 junction 软链）
   → npx cap sync android（自动写 capacitor.settings.gradle / capacitor.build.gradle / capacitor.plugins.json）
   → npm run build（Vite 打包 web 资源）
   → gradlew assembleDebug（JBR 21）
   → adb install → 启动 → Vue 调用插件 → 原生返回结果
```

实测结果：`NativeBasics` 插件的 `getDeviceInfo` 在模拟器返回
`{"manufacturer":"unknown","model":"Android SDK built for x86_64","osVersion":"16","sdkInt":36,"isEmulator":true}`，
`showToast` 正常弹出原生 Toast。

## 6. 常用命令

```powershell
# Web 开发
npm run dev

# 一键：web 构建 + cap sync + gradle + 安装 + 启动（自动挑设备，优先模拟器）
npm run android:deploy

# 只同步 web 资源与插件注册
npm run android:sync

# 在所有已连接设备（真机 + 模拟器）上跑内置冒烟测试，并给出 PASS/FAIL
npm run verify:android

# iOS 静态检查（本机可跑，不能替代 Xcode 编译）
npm run verify:ios

# 单独打 debug 包
cd android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```

### 6.1 自动化冒烟测试是怎么做的

`npm run verify:android` 不需要点屏幕：

1. `adb shell am start` 启动 App；
2. `adb forward tcp:93xx localabstract:webview_devtools_remote_<pid>` 把 WebView 的调试端口映射出来；
3. `scripts/webview-eval.mjs` 通过 Chrome DevTools Protocol 在页面里执行 `scripts/smoke-test.js`；
4. 冒烟脚本用 DOM 点击进入 Plugin Test 页、依次点按钮、读回渲染结果，返回 JSON；
5. PowerShell 汇总每台设备的 PASS/FAIL。

以后每个新插件，只要在 `src/views/PluginTestView.vue` 加一段测试，就能同时覆盖真机和模拟器。

> 注意：只有 debug 包才能这样调试（release 会关闭 WebView 调试）。

## 7. 已知限制

- **iOS 无法在本机编译**：Windows 上没有 Xcode。Swift 代码只能“盲写”，编译验证交给 GitHub Actions 的 macOS runner，方案见 [ios-without-mac.md](./ios-without-mac.md)。
- 真机安装需要 MIUI 放行（「开发者选项 → USB 安装」，安装时手机上点确认）；已实测成功。
- 模拟器是软件渲染，性能差，别拿它测动画流畅度。
