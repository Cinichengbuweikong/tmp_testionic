# capacitor-back-button 简介

`capacitor-back-button` 是一个给 Capacitor 8 应用用的**返回键控制插件**，作用相当于 Flutter 的
`PopScope`：把 Android 的硬件/手势返回键变成一个 JS 事件，交给前端决定"回到上一级路由"、
"退到后台"还是"退出应用"。

## 它解决什么问题

Capacitor 的 WebView 在 Android 上默认不处理返回键：用户按返回时，应用会**直接退出**，
而不是像浏览器一样回退前端路由。对多级页面的 App（商城、设置、详情页）来说，这往往就是
"按一下返回，应用没了"。

接入本插件后，你可以这样写：

```ts
await BackButton.enable();

BackButton.addListener('backButton', ({ canGoBack }) => {
  if (canGoBack) router.back();
  else BackButton.minimizeApp();   // 类似微信：退到桌面而不是杀进程
});
```

## 核心特性

- **默认不拦截**：不调用 `enable()` 时完全是系统默认行为，不会悄悄改变 App 的返回逻辑；
- **事件驱动**：`backButton` 事件带 `canGoBack`，由 JS 决定后续动作；
- **显式开关**：`enable()` / `disable()` / `isEnabled()` 可在运行时切换（例如只在某些页面拦截）；
- **内置动作**：`goBack()`（WebView 历史回退）、`minimizeApp()`（退到后台）、`exitApp()`（退出）；
- **iOS 侧滑返回**：`enable()` 时自动开启 WKWebView 的 `allowsBackForwardNavigationGestures`，
  也能用 `setSwipeBackEnabled()` 单独控制；
- **Web 降级**：`canGoBack` / `goBack` 用 `history` 模拟，不会让代码在浏览器里报错。

## 平台支持

| 能力 | Android | iOS | Web |
| --- | --- | --- | --- |
| 拦截返回键 / `backButton` 事件 | ✅ | ❌（系统无硬件返回键） | ❌ |
| `canGoBack` / `goBack` | ✅ | ✅ | ✅ |
| `minimizeApp` | ✅ | 不做任何动作 | 不做任何动作 |
| `exitApp` | ✅ | ❌ reject（Apple 不允许） | `window.close()`（通常被拦） |
| 侧滑返回手势 | —（系统手势由系统处理） | ✅ | — |

## 与 `@capacitor/app` 的区别

官方 `@capacitor/app` 也提供 `backButton` 事件，但：

- 它**默认就拦截**（除非配置 `disableBackButtonHandler`），且没有监听器时行为是
  "能回退就回退，否则什么都不做"（不会退出）；
- 它的 `backButton` 事件**只有 Android 实现**；iOS 的 `toggleBackButtonHandler` /
  `exitApp` / `minimizeApp` 都是空实现；
- 它不提供 iOS 侧滑返回。

本插件更适合"我想显式控制返回行为，且默认不打扰系统"的场景；如果你已经在用
`@capacitor/app` 管理生命周期，也可以直接用它的 `backButton`（少一个依赖）。

## 适用场景

- Vue Router / React Router 的多级页面，需要"返回 = 回上一级"；
- 首页希望"返回 = 退到桌面"（保留进程，类似微信）；
- 需要在某些页面禁用返回（表单未保存、视频播放中）等。

## 状态

- 版本：**0.2.0**
- 已验证：Android 真机（Redmi K30S / Android 10）、Android 16 模拟器；
  iOS 通过 GitHub Actions 编译 + 模拟器 autotest（侧滑手势手感待 iOS 真机确认）；
- 依赖：`@capacitor/core >= 8`，无第三方运行时依赖；
- Android 无需任何额外 Manifest 配置。
