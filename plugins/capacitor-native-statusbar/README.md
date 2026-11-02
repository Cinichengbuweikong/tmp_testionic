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
  在 MIUI（小米/红米）上也实测无效——这就是官方插件"设了颜色但没反应"的原因。

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

## 已知问题 / TODO

1. **MIUI 会按状态栏背景色自动决定图标明暗，并覆盖显式设置**（真机 Redmi K30S Ultra / MIUI 12 实测）：
   - 蓝色背景（`#2196F3`）→ 图标自动变白
   - 白色背景（`#FFFFFF`）→ 图标自动变深
   - 此时调用 `setStyle('light' | 'dark')` 均无效；`dumpsys window` 确认 `LIGHT_STATUS_BAR` 标志**已经**正确设置，
     是 MIUI 的 SystemUI 没有采纳。
   - 对照实验：在同一个 App、同一状态下直接调用官方 `@capacitor/status-bar` 的 `setStyle`，同样无效（像素级比对一致）。
   - 结论：这是 MIUI 的「对比度自适应」行为，插件侧暂时无法强制覆盖；在对比度正常的组合下（浅底配深图标、深底配浅图标），
     自动结果与期望一致。
2. **MIUI 劫持 WebView 的 forceDark**：同一台机器上网页 `getComputedStyle(body).backgroundColor` 是
   `rgb(255,255,255)`，但屏幕显示为近黑色。插件里已对窗口关闭 `setForceDarkAllowed(false)`（API 29+），
   实测页面会恢复正常颜色；如果不需要这个副作用可以去掉。

## 平台实现

| 平台 | 背景色 | 样式 |
| --- | --- | --- |
| Android | 自绘色块（+ `window.setStatusBarColor` 兜底） | `setAppearanceLightStatusBars` |
| iOS | 自绘 UIView，高度 = `safeAreaInsets.top` | `.lightContent` / `.darkContent` / `.default` |
| Web | 固定定位的色带（高度取 `env(safe-area-inset-top)`），仅用于开发预览 | 仅记录状态 |
