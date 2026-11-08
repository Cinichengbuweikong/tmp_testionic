import Foundation
import UIKit
import Capacitor

/**
 * WebViewToEdge 插件（iOS 侧，jsName = "WebViewToEdge"）。
 *
 * ============================ 这个插件解决什么 ============================
 *
 * 让 WebView 的内容铺满整屏、延伸到状态栏/底部 Home Indicator 下面
 * （不是隐藏系统栏的"全屏模式"），并且把安全区数值交给页面。
 *
 * iOS 的情况和 Android 不同：
 *   - Capacitor 的 `contentInsetAdjustmentBehavior` 默认就是 `.never`，
 *     WKWebView 本来就是铺满整屏的；页面只要写 `viewport-fit=cover` 就能拿到 `env(safe-area-inset-*)`。
 *   - 所以本插件在 iOS 上主要是：
 *       1. enable  → 确保 `.never`（内容贴边）；
 *          disable → 设为 `.automatic`（内容让出安全区，语义和 Android 的关闭一致）；
 *       2. 把 `safeAreaInsets` 写进 `--safe-area-inset-*` CSS 变量，和 Android 保持同一套用法；
 *       3. 旋转 / 前后台切换 / 布局变化时重新注入并派发 `insetsChanged`。
 *
 * ============================ 使用方式 ============================
 *
 * <pre>
 *   import { WebViewToEdge } from 'capacitor-webview-to-edge';
 *
 *   await WebViewToEdge.enable();
 *   const insets = await WebViewToEdge.getInsets(); // { top, right, bottom, left }
 * </pre>
 *
 * 页面里给顶部内容留空间：
 *
 * <pre>
 *   .header {
 *     padding-top: var(--safe-area-inset-top, env(safe-area-inset-top, 0px));
 *   }
 * </pre>
 */
@objc(WebViewToEdgePlugin)
public class WebViewToEdgePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "WebViewToEdgePlugin"
    public let jsName = "WebViewToEdge"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "enable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getInsets", returnType: CAPPluginReturnPromise)
    ]

    /// 是否开启"内容延伸到屏幕边缘"，默认关闭。
    private var enabled = false

    /// 上次派发过的安全区，用于去重。
    private var lastInsets = UIEdgeInsets.zero
    private var hasMeasured = false

    private var layoutObserver: LayoutObserverView?

    // MARK: - Lifecycle

    override public func load() {
        DispatchQueue.main.async { [weak self] in
            self?.installLayoutObserver()
            self?.updateInsets()
        }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    @objc private func handleDidBecomeActive() {
        updateInsets()
    }

    // MARK: - Plugin methods

    @objc func enable(_ call: CAPPluginCall) {
        setEnabledInternal(true, call: call)
    }

    @objc func disable(_ call: CAPPluginCall) {
        setEnabledInternal(false, call: call)
    }

    @objc func setEnabled(_ call: CAPPluginCall) {
        setEnabledInternal(call.getBool("enabled") ?? false, call: call)
    }

    @objc func isEnabled(_ call: CAPPluginCall) {
        call.resolve(["enabled": enabled])
    }

    @objc func getInsets(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self else {
                call.reject("Plugin deallocated")
                return
            }
            self.updateInsets()
            call.resolve(self.insetsDictionary(self.currentSafeAreaInsets()))
        }
    }

    // MARK: - Core

    private func setEnabledInternal(_ value: Bool, call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            guard let self else {
                call.reject("Plugin deallocated")
                return
            }
            self.enabled = value
            self.applyContentInsetBehavior()
            self.updateInsets()
            call.resolve([
                "enabled": self.enabled,
                "insets": self.insetsDictionary(self.currentSafeAreaInsets())
            ])
        }
    }

    /**
     * `contentInsetAdjustmentBehavior`：
     * - `.never`       → 内容贴边（延伸进安全区），对应 enable
     * - `.automatic`   → 系统按安全区调整内容，对应 disable
     */
    private func applyContentInsetBehavior() {
        guard let webView = bridge?.webView else {
            return
        }
        webView.scrollView.contentInsetAdjustmentBehavior = enabled ? .never : .automatic
        webView.scrollView.contentInset = .zero
    }

    private func currentSafeAreaInsets() -> UIEdgeInsets {
        bridge?.viewController?.view.safeAreaInsets ?? .zero
    }

    /// 重新读取安全区：变化时注入 CSS 变量并派发 insetsChanged。
    private func updateInsets() {
        let insets = currentSafeAreaInsets()
        injectCssVariables(insets)

        let changed =
            !hasMeasured ||
            insets.top != lastInsets.top ||
            insets.right != lastInsets.right ||
            insets.bottom != lastInsets.bottom ||
            insets.left != lastInsets.left

        lastInsets = insets
        hasMeasured = true

        if changed {
            notifyListeners("insetsChanged", data: insetsDictionary(insets))
        }
    }

    /// 把安全区写进页面的 CSS 变量（单位 pt，等价 CSS px）。
    private func injectCssVariables(_ insets: UIEdgeInsets) {
        guard let webView = bridge?.webView else {
            return
        }
        let js = """
        (function () {
          var root = document.documentElement;
          if (!root) { return; }
          root.style.setProperty('--safe-area-inset-top', '\(insets.top)px');
          root.style.setProperty('--safe-area-inset-right', '\(insets.right)px');
          root.style.setProperty('--safe-area-inset-bottom', '\(insets.bottom)px');
          root.style.setProperty('--safe-area-inset-left', '\(insets.left)px');
        })();
        """
        DispatchQueue.main.async {
            webView.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    private func insetsDictionary(_ insets: UIEdgeInsets) -> [String: Any] {
        [
            "top": Double(insets.top),
            "right": Double(insets.right),
            "bottom": Double(insets.bottom),
            "left": Double(insets.left)
        ]
    }

    // MARK: - Layout observer

    private func installLayoutObserver() {
        guard layoutObserver == nil, let host = bridge?.viewController?.view else {
            return
        }
        let observer = LayoutObserverView()
        observer.isHidden = true
        observer.frame = .zero
        observer.onLayout = { [weak self] in
            self?.updateInsets()
        }
        host.addSubview(observer)
        layoutObserver = observer
    }

    /// 隐藏视图，只用来感知父视图布局变化（旋转、分屏、安全区变化等）。
    private class LayoutObserverView: UIView {
        var onLayout: (() -> Void)?

        override func layoutSubviews() {
            super.layoutSubviews()
            onLayout?()
        }
    }
}
