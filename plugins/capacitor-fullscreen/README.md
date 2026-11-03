# capacitor-fullscreen

沉浸式全屏插件：**隐藏状态栏与导航栏**，让 WebView 内容完整铺满屏幕。支持状态查询、切换、变化事件监听（可解除）。

> 与 `capacitor-webview-to-edge` 的区别：
> - `webview-to-edge`：系统栏**仍然可见**，内容延伸/铺到它们下面（edge-to-edge）；
> - `capacitor-fullscreen`：系统栏**完全隐藏**（immersive 全屏），屏幕只剩应用内容。

需要注意的是 在和 capacitor-native-statusbar 插件同时使用的时候 由于 capacitor-native-statusbar 的实现原理 会导致系统的状态栏隐藏了 但状态栏的背景还在
如果需要 capacitor-native-statusbar 设置状态栏背景的话 那需要自行搭配 capacitor-webview-to-edge 来组合实现效果  如果不需要设置状态栏背景的话 则可尝试不使用 capacitor-native-statusbar 来设置状态栏背景

## 安装

```bash
npm install ./plugins/capacitor-fullscreen
npx cap sync
```

## 用法

```ts
import { Fullscreen } from 'capacitor-fullscreen';

// 进入全屏（隐藏状态栏 + 导航栏）
const state = await Fullscreen.enable();
// { fullscreen: true, statusBarHidden: true, navigationBarHidden: true }

// 退出全屏
await Fullscreen.disable();

// 切换
await Fullscreen.toggle();

// 设置指定状态
await Fullscreen.setFullscreen({ enabled: true });

// 读取当前状态（不改变状态）
const now = await Fullscreen.isFullscreen();

// 监听状态变化
const handle = await Fullscreen.addListener('fullscreenChanged', (s) => {
  console.log('全屏状态：', s.fullscreen);
});
await handle.remove();          // 解除这一个监听
await Fullscreen.removeAllListeners(); // 解除全部
```

## API 详细文档

### enable()

**功能**：进入全屏——隐藏状态栏与导航栏。

**签名**：`enable(): Promise<FullscreenState>`

**注意事项**：
- Android：使用 `WindowInsetsControllerCompat.hide(systemBars())` + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`，效果等同 immersive sticky——**从屏幕边缘滑动可临时唤出系统栏**，几秒后自动隐藏（系统行为，属正常）；
- iOS：隐藏状态栏；Home Indicator 请求系统自动隐藏（详见下方平台差异）；
- **布局自动适配**：系统栏隐藏后 insets 变为 0，Capacitor（或 webview-to-edge 插件）的 insets 监听会自动把 WebView padding 置 0，内容铺满整屏——插件本身不需要手动改布局。

---

### disable()

**功能**：退出全屏——恢复状态栏与导航栏。

**签名**：`disable(): Promise<FullscreenState>`

**注意事项**：恢复后 insets 重新出现，WebView padding 自动还原（沿用 Capacitor 默认行为）。

---

### setFullscreen(options)

**功能**：按指定状态设置全屏。

**签名**：`setFullscreen(options: { enabled: boolean }): Promise<FullscreenState>`

**参数**：

| 参数 | 类型 | 含义 |
| --- | --- | --- |
| `enabled` | `boolean` | `true` 进入全屏，`false` 退出 |

---

### toggle()

**功能**：切换全屏状态（当前全屏则退出，否则进入）。

**签名**：`toggle(): Promise<FullscreenState>`

---

### isFullscreen()

**功能**：读取当前全屏状态（不改变状态、不触发事件）。

**签名**：`isFullscreen(): Promise<FullscreenState>`

**注意事项**：返回的是**插件维护的状态**（由本插件的 enable/disable/setFullscreen/toggle 决定）。如果应用启动时通过 Capacitor 配置 `SystemBars.hidden=true` 直接进入全屏，插件初始状态仍为 `false`——这种场景请以首次 `enable()` 后的返回值为准，或调用一次 `enable()` 同步状态。

---

### addListener('fullscreenChanged', callback)

**功能**：监听全屏状态变化（由本插件的 enable/disable/setFullscreen/toggle 触发）。

**回调载荷**：`FullscreenState`（见下）。

**返回**：`PluginListenerHandle`，`handle.remove()` 解除这一个监听。

**注意事项**：
- **只在状态真正变化时派发**：重复 `enable()`（已全屏时）不会再触发事件；
- `removeAllListeners()` 可一次性解除本插件所有监听（Capacitor 桥内置方法）。

## FullscreenState 字段

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `fullscreen` | `boolean` | 是否处于全屏（系统栏隐藏） |
| `statusBarHidden` | `boolean` | 状态栏是否隐藏 |
| `navigationBarHidden` | `boolean` | 导航栏是否隐藏（iOS 上没有系统导航栏，见下） |

## 实现原理（关键实现）

| 平台 | 隐藏系统栏的机制 |
| --- | --- |
| Android | `WindowInsetsControllerCompat.hide(systemBars())` + `setSystemBarsBehavior(BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)`，与 Capacitor 8 内置 `SystemBars` 插件同一套 API；**不手动改 WebView 布局**——系统栏隐藏后 insets 变 0，Capacitor（或 `webview-to-edge` 插件）的 insets 监听会把 WebView padding 自动置 0，内容随之铺满；退出全屏 insets 恢复，padding 自动还原 |
| iOS | 状态栏走 Capacitor 公开 API `bridge.statusBarVisible = false/true`（内部触发 `CAPBridgeViewController.setStatusBarVisible` → `prefersStatusBarHidden`）；Home Indicator 借内置 `SystemBars` 插件的 `hide:/show:`（`@objc` 方法，运行时 `responds(to:)` 检查后 `perform` 调用），拿不到插件时**自动降级**为只隐藏状态栏 |
| Web | 浏览器 Fullscreen API：`document.documentElement.requestFullscreen()` / `document.exitFullscreen()`；监听 `fullscreenchange`（含 `webkit` 前缀）派发事件 |

**状态管理**：
- 插件自己维护 `fullscreen` 逻辑状态，初始为 `false`；
- **只在状态真正变化时**派发 `fullscreenChanged`——重复 `enable()`（已全屏）不会重复派发；
- `statusBarHidden` / `navigationBarHidden` 在 Android 上与 `fullscreen` 完全同步（两个栏总是一起隐藏/恢复）。

## 平台差异与注意事项

### Android

- 隐藏/恢复都用 androidx 的 `WindowInsetsControllerCompat`（与 Capacitor 8 内置 `SystemBars` 插件同一套 API）；
- 全屏后从屏幕边缘滑动会**临时**显示系统栏（immersive sticky 手感），这是 Android 系统行为，不是插件问题；
- 状态栏与导航栏**一起**隐藏/恢复，所以 `statusBarHidden` / `navigationBarHidden` 与 `fullscreen` 同步；
- 与 `capacitor-webview-to-edge` 插件**兼容**：系统栏隐藏后 insets 为 0，两个插件的 insets 处理都会自然得到"铺满"的结果；退出全屏后 padding 自动还原。

### iOS

- **状态栏**：通过 Capacitor 公开 API `bridge.statusBarVisible` 控制（内部走 `CAPBridgeViewController.setStatusBarVisible`，会触发 `prefersStatusBarHidden` 更新）；
- **Home Indicator**：iOS **不允许 App 直接隐藏**，只能"请求自动隐藏"（`prefersHomeIndicatorAutoHidden`）。Capacitor 内置的 `SystemBars` 插件已经 override 了该属性，本插件通过运行时调用它的 `hide:`/`show:`（`@objc` 方法，带存在性检查）来借用该能力：
  - 拿得到插件 → Home Indicator 自动隐藏/恢复 ✓
  - 拿不到（未来 Capacitor 改实现）→ **自动降级**：只隐藏状态栏，其他功能不受影响；
- 状态栏隐藏后顶部安全区变 0，WebView 内容自动铺满；
- iOS 上 `navigationBarHidden` 与 `fullscreen` 同步，但实际视觉由系统控制（Home Indicator 会短暂出现后淡出，属系统行为）。

### Web

- 用浏览器 Fullscreen API（`document.documentElement.requestFullscreen()`）实现，效果是隐藏浏览器 UI；
- `enable()` 需要在**用户手势**（如点击）中调用，否则浏览器会拒绝（插件会捕获异常、保持原状态）；
- 监听 `fullscreenchange` 事件并派发 `fullscreenChanged`；按 Esc 退出浏览器全屏时也会收到事件。

### 与其它插件的联动（通用注意点）

- **全屏期间系统栏是真的隐藏了**：此时 `capacitor-system-info` 的 `getStatusBarInfo()` / `getNavigationBarInfo()` 会返回 `height: 0`、`visible: false`（`getSafeAreaInsets()` 也会相应变为 0），这是正确行为，不是 bug；
- 与 `capacitor-rotation-listener` 无冲突：全屏状态下旋转屏幕，`rotationChanged` 照常派发；
- 与 `capacitor-webview-to-edge` 兼容：两者都依赖 insets 变化，全屏时 insets 归 0、退出时恢复，布局会自动跟上；
- 全屏隐藏系统栏会**改变视口尺寸**（`100vh` 变大），如需精确布局请在全屏切换后重新测量（或使用 `getViewport()`）。

## 实测数据（2026-11-03）

| 场景 | 结果 |
| --- | --- |
| 真机 K30S Ultra（MIUI 12）`enable()` 后截图 | 状态栏（时间/电量/信号）与底部导航栏完全消失，内容从屏幕最顶部渲染 ✅ |
| 真机 `disable()` 后截图 | 状态栏与导航栏完整恢复，布局还原 ✅ |
| 真机 API 返回 | `enable()` → `{fullscreen:true, statusBarHidden:true, navigationBarHidden:true}`；`disable()` → 全 `false` |
| 冒烟测试（真机 + 模拟器） | 进入/退出/读取状态/监听/取消监听 全 PASS |

## 开发

```bash
cd plugins/capacitor-fullscreen
npm install
npm run build        # tsc → dist/esm
```

- Android：`android/src/main/java/com/testionic/fullscreen/FullscreenPlugin.java`
- iOS：`ios/Sources/FullscreenPlugin/FullscreenPlugin.swift`（Windows 无法编译，靠 GitHub Actions 验证）
- 插件对外名称：npm 包 `capacitor-fullscreen`、`registerPlugin('Fullscreen')`、iOS SPM 包 `CapacitorFullscreen`
