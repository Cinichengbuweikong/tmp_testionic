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
| `minimizeApp()` | `void` | 退到后台（Android `moveTaskToBack`；iOS no-op） |
| `addListener('backButton', cb)` | `PluginListenerHandle` | 监听返回键；载荷 `{ canGoBack }` |
| `removeAllListeners()` | `void` | 移除所有监听 |

## 平台差异

| 能力 | Android | iOS | Web |
| --- | --- | --- | --- |
| 拦截硬件返回键 | ✅ `OnBackPressedCallback` | ❌（系统没有硬件返回键） | ❌（无法拦截浏览器后退） |
| `backButton` 事件 | ✅ | ❌ | ❌ |
| `canGoBack / goBack` | ✅ WebView 历史 | ✅ WKWebView 历史 | ✅ `history` |
| `exitApp` | ✅ `finish()` | ❌ reject（Apple 不允许） | `window.close()`（通常被拦） |
| `minimizeApp` | ✅ `moveTaskToBack(true)` | no-op | no-op |

> iOS 上 `enable()/disable()` 只记录状态，供 JS 统一调用；如果以后要支持"侧滑返回"，
> 需要另外监听 `WKNavigationDelegate`（不在本插件范围内）。

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

## 实测（Android 16 模拟器 / Pixel 6）

| 检查 | 结果 |
| --- | --- |
| `enable()` 后按返回键 | `backButton` 事件触发（连按 2 次，计数 2），页面 URL 不变、Activity 不退出 ✅ |
| `disable()` 后按返回键 | Activity 正常退出，回到桌面 ✅ |
| autotest | `enabled:false → true → false`，`canGoBack` 正常 ✅ |
