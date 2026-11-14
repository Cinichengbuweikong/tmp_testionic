import type { PluginListenerHandle } from '@capacitor/core';

/**
 * 返回键插件（`capacitor-back-button`）。
 *
 * 作用等价于 Flutter 的 `PopScope`：**接管 Android 硬件返回键**，把它变成
 * 一个 JS 事件，让前端（Vue Router / React Router 等）决定"回上一级路由"还是"退出 App"。
 *
 * 默认**不拦截**（保持系统行为：返回键直接退出 App）。调用 `enable()` 后才接管：
 *
 * ```ts
 * await BackButton.enable();
 * BackButton.addListener('backButton', () => {
 *   if (routerCanGoBack()) router.back();
 *   else void BackButton.minimizeApp(); // 或 exitApp()
 * });
 * ```
 *
 * 平台差异：
 * - **Android**：真正拦截硬件/手势返回键（`OnBackPressedCallback`）；
 * - **iOS**：系统没有硬件返回键，`enable/disable` 只记录状态；`canGoBack/goBack`
 *   基于 WKWebView 历史可用；`exitApp` 会 reject（Apple 不允许 App 主动退出）；
 * - **Web**：用 `history.back()` 模拟，`enable/disable` 无实际作用。
 */
export interface BackButtonPlugin {
  /**
   * 开启返回键拦截。
   *
   * 开启后，Android 上的返回键**不会**再触发默认行为（回退 WebView / 退出 App），
   * 而是派发 `backButton` 事件；事件处理函数必须自己决定后续动作
   * （`goBack()` / `minimizeApp()` / `exitApp()` / 路由跳转）。
   *
   * @since 0.1.0
   */
  enable(): Promise<{ enabled: boolean }>;

  /**
   * 关闭返回键拦截，恢复系统默认行为（Android 默认：退出 App）。
   *
   * @since 0.1.0
   */
  disable(): Promise<{ enabled: boolean }>;

  /**
   * 读取当前是否开启拦截。
   *
   * @since 0.1.0
   */
  isEnabled(): Promise<{ enabled: boolean }>;

  /**
   * 当前 WebView 是否有可回退的历史记录。
   *
   * 注意：单页应用（hash 路由）在首次进入时通常是 `false`；路由跳转后为 `true`。
   *
   * @since 0.1.0
   */
  canGoBack(): Promise<{ canGoBack: boolean }>;

  /**
   * 让 WebView 回退一页（仅当 `canGoBack()` 为 true 时生效）。
   *
   * @since 0.1.0
   */
  goBack(): Promise<{ wentBack: boolean }>;

  /**
   * 退出 App。
   *
   * - Android：`finish()` 当前 Activity；
   * - iOS：**会 reject**（Apple 不允许第三方 App 主动退出，App Store 审核会拒绝）。
   *
   * @since 0.1.0
   */
  exitApp(): Promise<void>;

  /**
   * 把 App 退到后台（"最小化"，不结束进程）。
   *
   * - Android：`moveTaskToBack(true)`；
   * - iOS：无对应概念，no-op（resolve）。
   *
   * 适合"在首页按返回键时不要退出，而是保留在后台"的交互（类似微信）。
   *
   * @since 0.1.0
   */
  minimizeApp(): Promise<void>;

  /**
   * 开启/关闭 iOS 的侧滑返回手势（WKWebView 的 `allowsBackForwardNavigationGestures`）。
   *
   * - **iOS**：从屏幕左边缘向右滑，会像 Safari 一样在 WebView 历史中后退；
   *   对 hash 路由的 SPA，效果等同于 `router.back()`（触发 `popstate`）。
   * - **Android**：没有这个手势，调用无效果，返回 `{ enabled: false }`。
   *
   * `enable()` 在 iOS 上会自动开启它；`disable()` 会自动关闭。
   * 如果只想单独控制手势，用这个方法。
   *
   * @since 0.2.0
   */
  setSwipeBackEnabled(options: { enabled: boolean }): Promise<{ enabled: boolean }>;

  /**
   * 读取 iOS 侧滑返回手势是否开启（Android 恒为 false）。
   *
   * @since 0.2.0
   */
  getSwipeBackEnabled(): Promise<{ enabled: boolean }>;

  /**
   * 监听返回键。仅在 `enable()` 之后才会触发。
   *
   * 载荷 `canGoBack` 表示事件发生时 WebView 是否有历史可回退。
   *
   * @since 0.1.0
   */
  addListener(
    eventName: 'backButton',
    listenerFunc: (event: BackButtonEvent) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * 移除本插件的所有监听。
   *
   * @since 0.1.0
   */
  removeAllListeners(): Promise<void>;
}

/** `backButton` 事件载荷。 */
export interface BackButtonEvent {
  /** 事件发生时 WebView 是否有可回退的历史。 */
  canGoBack: boolean;
}
