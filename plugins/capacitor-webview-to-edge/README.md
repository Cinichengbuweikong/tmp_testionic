# capacitor-webview-to-edge

**运行时 API**：让 WebView 的内容铺满整屏、延伸到状态栏（以及底部系统栏）下面。
默认关闭，调用 `enable()` 开启。

> 它**不是**"隐藏系统栏的全屏模式"——状态栏/导航栏仍然显示，只是页面内容可以画到它们下面。
> 也**不需要**改 `capacitor.config.json`，开关完全由 JS API 控制。

## 解决什么问题

Capacitor 8 默认会把 WebView 的内容让出状态栏：核心插件 `SystemBars` 会在根视图上装一个
insets 监听器，在 WebView 版本较低或页面没有 `viewport-fit=cover` 时给内容加 padding。

实测：

| 设备 | 默认 WebView 顶部位置 | 开启本插件后 |
| --- | --- | --- |
| Redmi K30S Ultra / Android 10 / WebView 87 | y = 90px | **y = 0** |
| Pixel 6 模拟器 / Android 16 / WebView 133 | y = 128px | **y = 0** |

iOS 侧 Capacitor 默认就是贴边的（`contentInsetAdjustmentBehavior = .never`），插件在这里主要做：
统一的安全区变量 + `enable/disable` 语义对齐。

## 安装

```bash
npm install ./plugins/capacitor-webview-to-edge
npx cap sync
```

## 用法

```js
import { WebViewToEdge } from 'capacitor-webview-to-edge';

// 开启：内容铺满整屏
await WebViewToEdge.enable();

// 关闭：恢复 Capacitor 默认（内容让出状态栏）
await WebViewToEdge.disable();

// 或者用布尔值（方便和设置项绑定）
await WebViewToEdge.setEnabled({ enabled: true });

// 查询状态 / 安全区
const { enabled } = await WebViewToEdge.isEnabled();
const insets = await WebViewToEdge.getInsets();
// { top, right, bottom, left }  单位 CSS px

// 旋转 / 键盘 / 分屏导致安全区变化
const handle = await WebViewToEdge.addListener('insetsChanged', (i) => {
  console.log(i.top);
});
```

## 页面怎么配合

开启后内容会跑到状态栏下面，所以**顶部内容要自己让出安全区**。插件会把安全区写成 CSS 变量：

```css
.header {
  padding-top: var(--safe-area-inset-top, env(safe-area-inset-top, 0px));
}

.bottom-bar {
  padding-bottom: var(--safe-area-inset-bottom, env(safe-area-inset-bottom, 0px));
}
```

> 为什么要用变量而不是直接用 `env()`：实测 Android WebView 87 上
> `env(safe-area-inset-top)` 恒为 `0px`（WebView 133 + Android 16 才正常返回 49px）。
> 插件注入的 `--safe-area-inset-*` 在所有 WebView 上都可用。

## API

| 方法 | 参数 | 返回 | 说明 |
| --- | --- | --- | --- |
| `enable` | — | `ToEdgeState` | 内容铺满整屏 |
| `disable` | — | `ToEdgeState` | 恢复默认（内容让出系统栏） |
| `setEnabled` | `{ enabled: boolean }` | `ToEdgeState` | 布尔开关 |
| `isEnabled` | — | `{ enabled }` | 当前状态 |
| `getInsets` | — | `SafeAreaInsets` | 安全区数值（CSS px） |
| `addListener` | `'insetsChanged'` | `PluginListenerHandle` | 安全区变化回调 |

`SafeAreaInsets`：`{ top, right, bottom, left }`
`ToEdgeState`：`{ enabled, insets }`

## 实现说明

### Android

- 在 DecorView 上安装自己的 `OnApplyWindowInsetsListener`（后装会覆盖 Capacitor `SystemBars` 的那个）；
- `enable` 时 padding 全部置 0；`disable` 时恢复成系统栏高度；
- 键盘弹出时始终保留底部 IME padding，避免输入框被挡；
- 把系统栏 insets 清零后再传给 WebView（和 Capacitor 的做法一致），安全区统一走插件注入的 CSS 变量；
- 旋转 / 分屏等导致 insets 变化时重新注入并派发 `insetsChanged`。

### iOS

- `enable` → `contentInsetAdjustmentBehavior = .never`；`disable` → `.automatic`；
- 用隐藏视图监听 `layoutSubviews`，在旋转/布局变化时重新注入 CSS 变量；
- 安全区来自 `viewController.view.safeAreaInsets`。

## 已验证环境

- Redmi K30S Ultra / Android 10 / MIUI 12 / WebView 87
- Pixel 6 模拟器 / Android 16 / WebView 133
- iOS 模拟器（GitHub Actions / Xcode 26）

## 已知限制

- 开启后内容会盖到状态栏下面，页面必须自己用 `--safe-area-inset-*` 留出空间，否则顶部内容会被状态栏压住；
- Android 上会覆盖 Capacitor `SystemBars` 的 insets 处理逻辑（这正是本插件的目的），
  因此 `SystemBars` 的 `insetsHandling` 配置对 WebView 内边距不再生效；
- 页面内 `env(safe-area-inset-*)` 在不同 WebView 版本上不可靠（见上文），请优先使用插件变量。
