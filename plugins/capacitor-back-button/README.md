# capacitor-back-button

Capacitor 返回键插件：**接管 Android 硬件返回键**，把它变成 JS 事件，让前端（Vue Router / React Router 等）决定"回上一级路由"还是"退出 / 最小化"。
作用等价于 Flutter 的 `PopScope`。

> 默认**不拦截**（保持系统行为：返回键直接退出 App）。调用 `enable()` 后才接管。

## 为什么需要它

Capacitor 的 WebView 在 Android 上默认不处理返回键：按返回键会**直接退出 App**，
而不是像浏览器那样回退前端路由。对于多级页面的 App（商城、设置页、详情页），
这会直接导致"按返回，应用没了"。

## 安装

```bash
npm install ./plugins/capacitor-back-button
npx cap sync
```

Android 侧无需任何额外配置（插件只用到 AndroidX 的 `OnBackPressedCallback`）。

## 用法（Vue Router）

```ts
import { BackButton } from 'capacitor-back-button';

// App 启动时开启拦截
await BackButton.enable();

BackButton.addListener('backButton', async () => {
  // 有上一级路由 → 回退；否则最小化（或 exitApp()）
  if (window.history.length > 1) {
    router.back();
  } else {
    await BackButton.minimizeApp(); // 类似微信：退到后台而不是杀掉
  }
});
```

组件卸载时可移除监听：

```ts
const handle = await BackButton.addListener('backButton', handler);
await handle.remove();
```

## API

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `enable()` | `{ enabled: boolean }` | 开启拦截：返回键改为派发 `backButton` 事件 |
| `disable()` | `{ enabled: boolean }` | 关闭拦截，恢复系统默认行为（Android 默认退出 App） |
| `isEnabled()` | `{ enabled: boolean }` | 当前是否开启 |
| `canGoBack()` | `{ canGoBack: boolean }` | WebView 是否有可回退历史 |
| `goBack()` | `{ wentBack: boolean }` | 让 WebView 回退一页 |
| `exitApp()` | `void` | 退出 App（**iOS 会 reject**） |
| `minimizeApp()` | `void` | 退到后台（Android `moveTaskToBack`；iOS 不做任何动作） |
| `setSwipeBackEnabled({ enabled })` | `{ enabled }` | **iOS**：开关 WKWebView 侧滑返回手势（Android 固定 false） |
| `getSwipeBackEnabled()` | `{ enabled }` | 读取侧滑手势开关（Android 恒 false） |
| `addListener('backButton', cb)` | `PluginListenerHandle` | 监听返回键；载荷 `{ canGoBack }` |
| `removeAllListeners()` | `void` | 移除所有监听 |

## API 字段详解

### `enable()` → `{ enabled: boolean }`

- **参数**：无。
- **返回**：
  - `enabled`：操作完成后是否处于拦截状态。
    - Android：`true` 表示已接管返回键（返回键只会派发 `backButton` 事件，不再退出/回退）；
    - iOS：仅表示插件内部状态，同时会**自动开启**侧滑返回手势；
    - Web：只记录状态（无法真正拦截浏览器后退）。

```ts
const { enabled } = await BackButton.enable(); // true
```

### `disable()` → `{ enabled: boolean }`

- **参数**：无。
- **返回**：
  - `enabled`：固定为 `false`（表示已关闭拦截）。
- **副作用**：Android 恢复系统默认返回行为（默认是退出 App）；iOS 会**自动关闭**侧滑返回手势。

### `isEnabled()` → `{ enabled: boolean }`

- **参数**：无。
- **返回**：
  - `enabled`：当前是否开启拦截（只读，不改变任何状态）。

### `canGoBack()` → `{ canGoBack: boolean }`

- **参数**：无。
- **返回**：
  - `canGoBack`：当前 WebView 是否有可回退的历史记录。
    - 单页应用首次进入首页时通常是 `false`；
    - 发生过路由跳转（`pushState`/hash 变化）后通常为 `true`。

### `goBack()` → `{ wentBack: boolean }`

- **参数**：无。
- **返回**：
  - `wentBack`：是否真的执行了回退。
    - `true`：有历史，已调用 `webView.goBack()`；
    - `false`：没有历史，**不会报错**，也不会有任何页面变化。

> 它只操作 **WebView 历史**，不会调用你的路由器；Vue Router 会通过 `popstate` 感知到并同步路由。

### `exitApp()` → `Promise<void>`

- **参数**：无。
- **返回**：无（`Promise<void>`）。
- **平台行为**：
  - Android：`finish()` 当前 Activity（应用退出）；
  - iOS：**reject**，错误信息为"exitApp 在 iOS 上不可用：Apple 不允许第三方 App 主动退出"；
  - Web：`window.close()`（通常会被浏览器拦截，仅脚本打开的窗口有效）。

### `minimizeApp()` → `Promise<void>`

- **参数**：无。
- **返回**：无（`Promise<void>`）。
- **平台行为**：
  - Android：`moveTaskToBack(true)`，把任务退到后台（**进程保留**，类似微信返回桌面）；
  - iOS / Web：不做任何动作（直接 resolve）。

### `setSwipeBackEnabled(options)` → `{ enabled: boolean }`

- **参数** `options`：

  | 字段 | 类型 | 必填 | 默认 | 说明 |
  | --- | --- | --- | --- | --- |
  | `enabled` | `boolean` | 否 | `true` | `true` 开启侧滑返回；`false` 关闭 |

- **返回**：
  - `enabled`：实际生效的开关状态。
    - iOS：返回你设置的值（并立即作用到 WKWebView 的 `allowsBackForwardNavigationGestures`）；
    - Android：**恒为 `false`**（Android 没有这个手势，调用无效果）。

```ts
await BackButton.setSwipeBackEnabled({ enabled: true });  // iOS: 开启
await BackButton.setSwipeBackEnabled({ enabled: false }); // iOS: 关闭（如轮播图冲突时）
```

> `enable()` 会自动开启、`disable()` 会自动关闭；这个方法用于**单独**控制手势，不改变 `enable()` 的拦截状态。

### `getSwipeBackEnabled()` → `{ enabled: boolean }`

- **参数**：无。
- **返回**：
  - `enabled`：侧滑手势当前是否开启；Android 恒为 `false`。

### `addListener('backButton', listenerFunc)` → `Promise<PluginListenerHandle>`

- **参数**：

  | 字段 | 类型 | 说明 |
  | --- | --- | --- |
  | `eventName` | `'backButton'` | 固定事件名 |
  | `listenerFunc` | `(event: BackButtonEvent) => void` | 返回键被按下时调用 |

- **事件载荷 `BackButtonEvent`**：

  | 字段 | 类型 | 说明 |
  | --- | --- | --- |
  | `canGoBack` | `boolean` | **事件发生时** WebView 是否有可回退历史，可用于决定"回路由"还是"最小化/退出" |

- **返回** `PluginListenerHandle`：`{ remove(): Promise<void> }`，调用 `remove()` 解除本次监听。

```ts
const handle = await BackButton.addListener('backButton', (event) => {
  console.log(event.canGoBack); // boolean
  if (event.canGoBack) window.history.back();
  else void BackButton.minimizeApp();
});
// 之后：await handle.remove();
```

> 只有在 `enable()` 之后才会收到事件；可以注册多个监听器，都会收到同一事件。
> iOS / Web 上不会触发该事件（iOS 没有硬件返回键）。

### `removeAllListeners()` → `Promise<void>`

- **参数**：无。
- **返回**：无。
- **说明**：移除本插件的所有 `backButton` 监听（等价于逐个 `handle.remove()`）。

### 错误与边界速查

| 场景 | 结果 |
| --- | --- |
| 在 iOS 调 `exitApp()` | reject（Apple 不允许主动退出） |
| `goBack()` 时没有历史 | `{ wentBack: false }`，不报错、页面不变 |
| 开启拦截但没注册监听 | 返回键被消费但无人处理 → 表现为"按返回没反应" |
| 在 Android 调 `setSwipeBackEnabled({ enabled: true })` | 返回 `{ enabled: false }`（无此手势） |
| 在 iOS 期望 `backButton` 事件 | 不会触发（改为使用侧滑手势或自绘返回按钮） |
| `addListener` 之后忘记 `remove` | 监听器会一直存在，页面重复挂载时会重复处理 |
## 平台差异

| 能力 | Android | iOS | Web |
| --- | --- | --- | --- |
| 拦截硬件返回键 | ✅ `OnBackPressedCallback` | ❌（系统没有硬件返回键） | ❌（无法拦截浏览器后退） |
| `backButton` 事件 | ✅ | ❌ | ❌ |
| `canGoBack / goBack` | ✅ WebView 历史 | ✅ WKWebView 历史 | ✅ `history` |
| `exitApp` | ✅ `finish()` | ❌ reject（Apple 不允许） | `window.close()`（通常被拦） |
| `minimizeApp` | ✅ `moveTaskToBack(true)` | 不做任何动作 | 不做任何动作 |
| 侧滑返回手势 | —（系统返回手势由系统处理） | ✅ `allowsBackForwardNavigationGestures`（`enable()` 时自动开） | — |

> iOS 上 `enable()/disable()` 只记录状态，并会**自动开启/关闭侧滑返回手势**（见下）。

## iOS 侧滑返回（Safari 风格）

iOS 没有硬件返回键，原生应用一般靠 "左上角返回按钮 + 左边缘侧滑" (实测 Safari 的页面切换规则是 从左到右滑动返回上一页 从右到左滑动返回下一页 我想或许是 iOS 上系统为 webview 组件内实现了三个实际的展示内容的组件(或许这三个展示内容的组件同时使用一个 webview runtime?)  一个展示上一页 一个展示当前页 一个展示之前来到的下一页  iOS 内部就识别手势并在这几个内容组件之间来回切换展示  实测 iOS 是在页面彻底划出屏幕的一瞬间完成当前 webview 的路由切换的)。Capacitor 默认两者都没有（它是单个 `CAPBridgeViewController`，没有 `UINavigationController`，也没开 WKWebView 的手势）。

本插件在 `enable()` 时会设置：

```swift
webView.allowsBackForwardNavigationGestures = true
```

于是 iOS 上可以**从左边缘向右滑**，像 Safari 一样在 WebView 历史里后退。
对 hash 路由的 SPA（Vue Router / React Router），效果等同于 `router.back()`（会触发 `popstate`）。

也可以单独控制：

```ts
await BackButton.setSwipeBackEnabled({ enabled: true });
const { enabled } = await BackButton.getSwipeBackEnabled();
```

**注意**：

1. 手势依赖 **WebView 历史栈**：在首页（没有历史）时滑不出上一页，这是正常的；
2. 如果页面内部有水平滑动组件（轮播图），边缘手势可能和它冲突——可以按页面
   `setSwipeBackEnabled({ enabled: false })` 临时关掉；
3. Android 没有这个手势（系统返回手势由系统自己处理，`enable()` 的拦截逻辑已经覆盖）；
4. 左上角返回按钮仍然需要你自己在页面里画（Capacitor 不提供原生导航栏），点击时调 `router.back()`。
## 工作方式

- Android：插件在 `load()` 里向 Activity 的 `OnBackPressedDispatcher` 注册一个
  `OnBackPressedCallback`，默认 `enabled=false`；`enable()` 时打开它，返回键不再走默认
  `finish()`，而是 `notifyListeners("backButton", { canGoBack })`。`disable()` 后恢复默认。
- 与 `@capacitor/app` 的关系：官方 App 插件也提供 `backButton` 事件；本插件是独立实现，
  额外提供 `enable/disable`、`canGoBack/goBack`、`minimizeApp`。如果项目已经用
  `@capacitor/app` 的 `backButton`，不必两个一起用。

## 注意

1. **`enable()` 之后必须处理事件**：不处理的话返回键会"没反应"（事件被消费了）。
   推荐处理函数里 `router.back()` / `minimizeApp()` / `exitApp()` 三选一。
2. 事件里的 `canGoBack` 是**事件发生时** WebView 的历史状态；SPA 首次进入通常为 `false`。
3. 如果只在某些页面需要拦截，可以在页面 `mounted` 时 `enable()`、`beforeDestroy` 时 `disable()`。
4. 不要在 `enable()` 状态下忘记 `disable()`，否则启动页/登录页也会被拦截。

## 实测（真机 Redmi K30S / Android 10 + 模拟器 Pixel 6 / Android 16）

| 检查 | 结果 |
| --- | --- |
| `enable()` 后按返回键 | `backButton` 事件触发（连按 2 次，计数 2），页面 URL 不变、Activity 不退出 ✅ |
| `disable()` 后按返回键 | Activity 正常退出，回到桌面 ✅ |
| autotest | `enabled:false → true → false`，`canGoBack` 正常 ✅ |
| 真机：路由回退 | `#/about` 按返回 → `#/`，事件 1 次、`canGoBack:true`、App 存活 ✅ |
| 真机：首页最小化 | 监听里调 `minimizeApp()`，按返回 → 回到桌面、进程存活 ✅ |
| 真机：关闭拦截 | `disable()` 后按返回 → Activity 退出、回到桌面 ✅ |
| 真机：完整 autotest | `ok: true`（首次偶发 data-channel 超时，重跑通过） |
| iOS 侧滑返回 | CI 编译通过；真实手感仍需 iOS 真机确认（无 Mac） |
