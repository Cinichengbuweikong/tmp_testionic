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

真机上开关一次的实际表现（用 `uiautomator dump` 读的 WebView bounds）：

| 状态 | WebView bounds | 说明 |
| --- | --- | --- |
| 默认（未调用） | `[0,90][1080,2180]` | 内容让出状态栏 |
| `enable()` | `[0,0][1080,2180]` | 顶到屏幕最上方，insets `{ top: 33, bottom: 0 }` |
| `disable()` | `[0,90][1080,2180]` | 恢复默认 |

模拟器（Android 16 / WebView 133）开启后为 `[0,0][1080,2400]`，insets `{ top: 49, bottom: 48 }`。

## 取舍与注意事项

### 1. 为什么把 insets 清零、坚持用 CSS 变量

Android 端在把 insets 继续传给 WebView 之前**清零了系统栏部分**（和 Capacitor `SystemBars` 的做法一致），
页面统一使用插件注入的 `--safe-area-inset-*`。

- ✅ 好处：不会出现"系统安全区 + 插件变量"双重叠加；老 WebView（87）和新 WebView（133）行为完全一致；
- ⚠️ 代价：**Android 上页面里的 `env(safe-area-inset-*)` 恒为 `0px`**。
  如果你有现成代码依赖 `env()`，请改成 `var(--safe-area-inset-top, env(safe-area-inset-top, 0px))` 这种写法，
  或者用 `getInsets()`。

实测过为什么不能直接依赖 `env()`：

| 设备 | `env(safe-area-inset-top)` |
| --- | --- |
| 真机 WebView 87 | `0px` ❌ |
| 模拟器 WebView 133 | `49px` ✅ |

### 2. 会接管 Capacitor `SystemBars` 的内边距逻辑

插件在 DecorView 上装了自己的 `OnApplyWindowInsetsListener`，后装的会覆盖 `SystemBars` 的那个。
所以：

- `SystemBars` 的 `insetsHandling` 配置对 **WebView 内边距**不再生效（这正是本插件的目的）；
- `SystemBars` 的状态栏样式（`style` / `hidden`）不受影响，仍然正常工作；
- 如果以后 Capacitor 改了 insets 实现，可能需要重新确认这里的覆盖顺序。

### 3. 键盘（IME）的处理

`enable` 状态下四边 padding 都是 0，但**键盘弹出时会保留底部 IME padding**，
否则输入框会被键盘挡住。也就是说"铺满整屏"只作用于状态栏/导航栏，不影响键盘避让。

### 4. 两端语义对齐，但视觉细节可能略有差异

- Android：`enable` = padding 全 0；`disable` = 恢复系统栏高度 padding；
- iOS：`enable` = `contentInsetAdjustmentBehavior.never`；`disable` = `.automatic`。

iOS 本来默认就是贴边的，所以 `disable()` 在 iOS 上是"让系统按安全区调整内容"，
和 Android 的"内容让出状态栏"语义一致，但两端在滚动行为上可能有细微差别。

### 5. 页面必须自己留出顶部空间

开启后内容会盖到状态栏/刘海下面，插件不会帮你把内容往下推。至少给顶部容器加：

```css
padding-top: var(--safe-area-inset-top, env(safe-area-inset-top, 0px));
```

底部如果有固定操作栏，同理用 `--safe-area-inset-bottom`。

### 6. 调用时机与闪动

- 默认关闭，**不调用就不会有任何变化**；
- 插件加载时就已经装好监听器，`enable()` 调用后会立即触发一次重新布局（insets 分发是异步的，通常下一帧生效）；
- 如果希望首屏就是铺满的，建议在 `mounted` / 启动阶段尽早调用 `enable()`，避免"先让出、再铺满"的跳动。

### 7. 和状态栏插件的关系

本插件只管"WebView 铺到哪"，**不管状态栏图标颜色**。
铺满后状态栏可能压在深色内容上导致看不清，需要配合 `capacitor-native-statusbar` 调整图标亮暗。
