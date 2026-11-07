# capacitor-native-statusbar

真正能生效的 Capacitor 状态栏插件：**设置状态栏背景色** + **设置状态栏文字/图标样式（亮色 / 暗色 / 默认）**。

## 为什么不用官方 `@capacitor/status-bar`？

先澄清一个常见误解：**状态栏那一条并不是网页画的**。

实测（Android 10 真机 + Android 16 模拟器，用 `uiautomator dump` 看 View 层级）：

- WebView 的内容区从状态栏**下方**开始（真机 y=90px、模拟器 y=128px），所以 `:root` / `body` 的
  背景色永远只覆盖内容区，**不会**跑到状态栏里；
- 但 App 的窗口本身是从屏幕 `y=0` 开始的，DecorView 只是用内边距把内容顶到状态栏下方；
- 状态栏那一条由窗口/主题绘制（默认深灰 `#303030`）；
- `window.setStatusBarColor()` 在 Android 15+（targetSdk 35+）会被系统直接忽略；
  在 MIUI 12（Redmi K30S Ultra / Android 10）上也实测无效——这就是官方插件"设了颜色但没反应"的原因。

官方插件 `getInfo()` 里的 `overlays` 字段只是读了 `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` 这个旧 flag，
并不代表 WebView 真的铺到了状态栏下面，别被它误导。

所以本插件改成**自己画一条**：

- Android：在窗口顶部加一条与状态栏等高的色块（负 margin 抵掉 DecorView 内边距 + 关闭 `clipToPadding`），
  同时仍然调用系统 API 作为旧设备兜底；
- iOS：iOS 的状态栏本来就没有背景色，在 VC 的 view 上铺一条等高 UIView；
- 两端高度都用 insets / safeArea 计算，旋转、刘海、挖孔都会自动适配。

## 安装

```bash
npm install ./plugins/capacitor-native-statusbar
npx cap sync
```

## 用法

```js
import { NativeStatusBar } from 'capacitor-native-statusbar';

// 背景色（支持 #RGB / #RRGGBB / #AARRGGBB / transparent）
await NativeStatusBar.setBackgroundColor({ color: '#FF5722' });

// 内容样式
await NativeStatusBar.setStyle({ style: 'light' });  // 白色图标，适合深色底
await NativeStatusBar.setStyle({ style: 'dark' });   // 黑色图标，适合浅色底
await NativeStatusBar.setStyle({ style: 'default' }); // 跟随系统深浅色

// 调试用
const info = await NativeStatusBar.getInfo();
// { color: '#FFFF5722', style: 'light', height: 24, usingOverlay: true }
```

## API

| 方法 | 参数 | 返回 |
| --- | --- | --- |
| `setBackgroundColor` | `{ color: string }` | `{ color: string, height: number }` |
| `setStyle` | `{ style: 'light' \| 'dark' \| 'default' }` | `void` |
| `getInfo` | — | `{ color, style, height, usingOverlay }` |

颜色规则：`#RGB`、`#RRGGBB`、`#AARRGGBB`（**ARGB** 顺序，与 Android 一致）、`transparent`。

## 注意事项

1. **色块只盖住状态栏那一条**：网页内容不会被顶下去，所以如果你的页面顶部贴边，
   需要在 Web 层让出安全区，例如：

   ```css
   .app-header {
     padding-top: env(safe-area-inset-top);
   }
   ```

   在 Capacitor 里建议连 `viewport-fit=cover` 一起用。

   > 注：本插件的实现前提是「窗口从屏幕 y=0 开始、WebView 内容区被内边距顶到状态栏下方」，
   > 这也是 Capacitor 8 + Android 原生侧的默认布局。如果你在原生层改成了真正的 edge-to-edge
   > （内容铺到状态栏下面），`height` 仍然可用，但色块会盖住内容顶部的一部分。

2. `transparent` 会移除色块，恢复到"内容直接透过状态栏"的效果。
3. `height` 单位是 CSS px（Android dp / iOS pt），可以用它来做自定义布局。
4. 深色 / 浅色样式在 Android 上依赖 `WindowInsetsControllerCompat`，最低支持到 API 23；
   iOS 用 `CAPBridgeViewController.setStatusBarStyle`。

## 已知问题 / 设计取舍

> **测试环境**：以下结论全部基于 **Redmi K30S Ultra / Android 10 / MIUI 12**（另有一台 Pixel 6 模拟器 / Android 16 做对照）。
> MIUI 每个大版本都可能重写系统栏实现，换到 MIUI 14/15、HyperOS 或其它 Android 版本后如果行为变了，
> 请按下面的步骤重新实测，再判断是改代码还是更新结论。

### 1. MIUI 12 的「对比度自适应」会覆盖图标亮暗（已确认，非本插件 bug）

真机 Redmi K30S Ultra（Android 10 / MIUI 12）实测：

| 状态栏背景 | MIUI 实际渲染的图标 |
| --- | --- |
| 蓝色 `#2196F3` | 自动变白 |
| 白色 `#FFFFFF` | 自动变深 |

此时显式调用 `setStyle('light')` / `setStyle('dark')` **均无效**。排查过程：

- `dumpsys window` 确认标志位**已经**正确写入：
  `vsysui=... LIGHT_STATUS_BAR`（dark 样式） / 没有该 flag（light 样式）；
- 对照实验：同一个 App、同一状态下直接调用**官方** `@capacitor/status-bar` 的 `setStyle`，
  从 `Style.Dark` 切到 `Style.Light`，两张截图逐像素比对：
  **右上角信号/电池图标区域差异 0 / 5320 像素** —— 官方插件同样改不了；
- 结论：MIUI 按状态栏背景色做对比度自适应，会无视 App 的请求。

**设计取舍**：不去对抗它。对比度正常的组合（浅底配深图标、深底配浅图标）自动结果本来就正确；
强行要求低对比度组合（如浅底配白图标）时 MIUI 会拒绝，这在可用性上反而是合理的。
所以在 MIUI 上「图标亮暗」表现为"跟随背景色自动切换"。

### 2. 暗色模式下「白色失效」：色块被系统 force dark 反色（0.2.0 已修复）

真机实测（Redmi K30S Ultra / Android 10 / MIUI 12，**系统处于暗色模式**）：

| 设置的颜色 | 修复前状态栏实际像素 | 修复后 |
| --- | --- | --- |
| `#FFFFFF` | **rgb(27,27,27) 近黑**（白色被完全反色） | rgb(228,232,227) 近白 ✅ |
| `#FF5722` | rgb(216,50,0) = `#D83200`（被压暗） | **rgb(255,87,34) = `#FF5722` 精确原色** ✅ |

此时 `getInfo()` 一直返回 `#FFFFFFFF`、`usingOverlay: true`——**色块本身是画出来了的**，是被系统渲染层改色。

**根因**：Android Q（10）引入的 **force dark**——系统暗色模式下对 App 内的 View 做"智能反色"，
MIUI 12 对第三方 App 默认启用。白色（高亮度）会被整体反转为近黑色，橙色等中间色被压暗，
这与 WebView 层的 forceDark 是同一类机制，但作用在**原生 View 树**上。

**修复**：给自绘的状态栏色块**单独**关闭 force dark（只影响这一条色块，不动全局）：

```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    overlayView.setForceDarkAllowed(false);   // API 29+
}
```

注意：不要用窗口级/主题级的 `android:forceDarkAllowed="false"`——那会关掉整个 App 的
暗色适配；只对色块 View 关闭即可获得"设置什么颜色就显示什么颜色"的效果。

### 3. 职责边界：WebView 内容的 forceDark 不归本插件管

MIUI 12 会向 WebView 注入 forceDark（同一台机器上 `getComputedStyle(document.body).backgroundColor`
算出来是 `rgb(255,255,255)`，屏幕却显示成近黑色）。这是**页面/WebView 层**的问题，不是状态栏的问题。

本插件只负责「状态栏背景色 + 图标样式」两件事，**不处理 WebView 的 forceDark**。
（第 2 节对色块 View 关闭 force dark 是精准处理，不会影响 WebView 内容；
如果页面颜色也有问题，请用 `capacitor-set-force-dark` 插件或页面层的 CSS 方案。）

### 4. 旋转 / 横竖屏切换（Android 0.3.0 已修复）

**bug 现象**（Pixel 6 模拟器 / Android 16，竖屏状态栏 128px、横屏 63px）：

| 步骤 | 修复前 | 修复后 |
| --- | --- | --- |
| 竖屏 | 色块 rows 0..127（128px）✅ | 128px ✅ |
| 转横屏 | 63px ✅ | 63px ✅ |
| **转回竖屏** | **rows 65..127（63px）❌ 只有一半高、还整体下移 65px** | **rows 0..127（128px）✅** |

**根因**：色块高度靠 `ViewCompat.getRootWindowInsets()` 读状态栏 Insets，但
`handleOnConfigurationChanged` 触发时 **DecorView 还没按新方向重新布局**，拿到的是**旧方向**的 Insets
（转回竖屏时读到横屏的 63px），而 `topMargin = -paddingTop` 用的是**新**方向的 128px
→ 色块落在 `128-63=65` 到 `128`。

**修复**：把几何计算从"配置回调时刻"移到 **`OnGlobalLayoutListener`**（布局完成后 padding/Insets 都已更新），
并做"值没变就不 `setLayoutParams`"的去重，避免 `requestLayout` 回调死循环。
配置回调仍然保留（用于重新套用颜色/样式），但几何以布局回调为准。

**iOS 不受影响**：色块用的是 Auto Layout（`topAnchor == host.top`、
`bottomAnchor == safeAreaLayoutGuide.top`），旋转/横屏由系统的安全区自动驱动，不存在"缓存高度"的问题
（所以本次没有改 iOS 代码）。

**复现/验证方法（adb，模拟器）**：

```bash
# 关自动旋转，固定竖屏，设置一个纯色便于量像素
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
# 在页面里调用 setBackgroundColor({ color: '#FF0000' })
adb exec-out screencap -p > portrait.png

adb shell settings put system user_rotation 1      # 转横屏
adb exec-out screencap -p > landscape.png
adb shell settings put system user_rotation 0      # 转回竖屏
adb exec-out screencap -p > back.png
# 量三张图顶部"纯红"连续行数：应为 128 / 63 / 128（模拟器），真机为 90 / 90 / 90
```

实测（修复后）：模拟器 128 / 63 / **128** / 128（两轮旋转）；真机 Redmi K30S 90 / 90 / 90（该机型横竖屏同高）。

### 5. 其它已知边界

- 只在 Android 10（真机）和 Android 16（模拟器）上验证过；Android 15+ 走的是自绘色块路径，理论一致。
- iOS 侧没有 MIUI 这类覆盖行为，`setStyle` 设置什么就是什么。

## 平台实现

| 平台 | 背景色 | 样式 |
| --- | --- | --- |
| Android | 自绘色块（+ `window.setStatusBarColor` 兜底） | `setAppearanceLightStatusBars` |
| iOS | 自绘 UIView，高度 = `safeAreaInsets.top` | `.lightContent` / `.darkContent` / `.default` |
| Web | 固定定位的色带（高度取 `env(safe-area-inset-top)`），仅用于开发预览 | 仅记录状态 |
