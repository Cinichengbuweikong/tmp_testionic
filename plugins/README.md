# plugins/ — 自定义 Capacitor 插件集合

这里存放为本项目（以及未来的项目）自己写的 Capacitor 插件。每个插件是一个**独立的 npm 包**，可以被任意 Capacitor 应用用 `file:` 方式引入，也可以日后发布到 npm / 私有 registry。

## 现有插件

| 插件 | Android | iOS | Web | 说明 |
| --- | --- | --- | --- | --- |
| [capacitor-native-basics](./capacitor-native-basics/) | ✅ 已验证 | 🟡 已实现，待 CI 编译验证 | ✅ | 原生 Toast、设备信息、echo 自检（参考实现） |
| [capacitor-native-statusbar](./capacitor-native-statusbar/) | ✅ 已验证 | ✅ CI 通过 | ✅ | 状态栏背景色 + 图标亮暗（绕过 Android 15+/MIUI 的 setStatusBarColor 失效；0.2.0 修复暗色模式 force dark 导致白色失效） |
| [capacitor-set-force-dark](./capacitor-set-force-dark/) | ✅ 已验证 | ✅ CI 通过 | ✅ | 设置/关闭 WebView 的 forceDark，注入 data-theme/color-scheme 并派发主题变化事件 |
| [capacitor-webview-to-edge](./capacitor-webview-to-edge/) | ✅ 已验证 | ✅ CI 通过 | ✅ | 运行时开关：WebView 内容铺满整屏（延伸进状态栏），并提供安全区 insets |
| [capacitor-system-info](./capacitor-system-info/) | ✅ 已验证 | ✅ CI 通过 | ✅ | 屏幕/视口/安全区/系统栏/系统/WebView/内存/电量/网络 等 13 个独立只读 API |
| [capacitor-native-rotation](./capacitor-native-rotation/) | ✅ 已验证 | 🔨 待 CI 验证 | ✅ | 屏幕方向：getRotation + rotationChanged 事件 + 主动锁定横屏/竖屏（视频全屏播放场景） |
| [capacitor-fullscreen](./capacitor-fullscreen/) | ✅ 已验证 | ✅ CI 通过 | ✅ | 沉浸式全屏：隐藏状态栏+导航栏，带状态事件（可监听/解除） |

## 新增插件的标准流程

1. 复制 `capacitor-native-basics` 作为骨架（改包名、`capacitor` 字段、Java 包名、`@CapacitorPlugin(name=...)`）。
2. 写 `src/definitions.ts`（API 契约）→ `src/web.ts`（浏览器降级）→ `android/.../XxxPlugin.java`（原生实现）。
3. 插件目录里 `npm install && npm run build`。
4. 应用根目录：`npm install ./plugins/<plugin-name>`。
5. `npx cap sync android`，命令行应打印 `Found N Capacitor plugins for android` 并包含你的插件。
6. `npm run android:deploy` 部署到模拟器验证。

## Capacitor CLI 的识别规则（踩过的坑）

- 插件 `package.json` 必须有 `capacitor` 字段，否则 CLI 不会把它当插件：
  ```json
  "capacitor": { "android": { "src": "android" } }
  ```
- Android 插件类必须满足 CLI 的正则扫描：
  - `package xxx.yyy;` 必须**顶格**写（不能有缩进）；
  - `@CapacitorPlugin(...)` 也必须顶格，且同一文件里随后出现 `class XxxPlugin`。
- Gradle 模块名 = 包名去掉 `@`、把 `/` 换成 `-`（如 `@scope/foo` → `scope-foo`）。
- `compileSdk` / `minSdk` / Java 版本从宿主工程的 `variables.gradle` 读取，插件里要写好 `project.hasProperty(...)` 的兜底值，这样插件单独打开也能编译。

## 插件工程约定

- Java 21（跟随宿主工程 `capacitor.build.gradle`），AGP 8.13.0，compileSdk 36 / minSdk 24。
- 每个插件自带 `README.md` 写清 API 与用法；`definitions.ts` 里用 JSDoc 写 `@since`。
- Web 端必须提供降级实现（`src/web.ts`），保证 `npm run dev` 和 PWA 可用。

## 与 iOS 的关系

开发机是 Windows，**没有 Xcode，无法在本机编译/运行 iOS**。现在的做法是：

- 每个插件照官方模板写 `Package.swift` + `ios/Sources/<Class>Plugin/<Class>Plugin.swift`，并在 `package.json` 的 `capacitor.ios` 里声明；
- `npx cap sync ios` 会把插件注册进 `ios/App/CapApp-SPM/Package.swift`；
- 本机跑 `npm run verify:ios` 做静态一致性检查；
- 真正编译交给 `.github/workflows/ios.yml`（GitHub Actions 的 macOS runner，会编译 + 起模拟器 + 截图）。

详见 [../docs/ios-without-mac.md](../docs/ios-without-mac.md)。

### iOS 插件注意点

- `CAPBridgedPlugin` 必须提供 `identifier`、`jsName`、`pluginMethods` 三个成员，缺一个都不会被注册；
- `@objc(<Class>Plugin)` 一定要加，否则桥接找不到类；
- `pluginMethods` 里的方法名必须与 `@objc func xxx(_ call: CAPPluginCall)` 完全一致；
- 没有 Xcode 时，Swift 代码只能靠 CI 编译验证，改完记得 push 跑一次 workflow。
