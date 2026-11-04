import Foundation
import UIKit
import Capacitor

/**
 * KeyboardAvoiding 插件（iOS 侧，jsName = "KeyboardAvoiding"）。
 *
 * ======================= 这个插件解决什么 =======================
 *
 * 1. 监听键盘弹出/隐藏，派发 keyboardChanged 事件（带键盘高度、可用视口高度）；
 * 2. 可选的自动避让（enable()）。
 *
 * ======================= 关键实现 =======================
 *
 * iOS 的 WKWebView（Capacitor 设了 contentInsetAdjustmentBehavior = .never）**不会**自动
 * 把内容缩到键盘上方，所以 iOS 才是自动避让的主战场：
 *   - 监听 UIResponder.keyboardWillChangeFrameNotification / keyboardWillHideNotification
 *     （willChangeFrame 会覆盖"键盘出现/消失/切换输入法"等所有情况）；
 *   - 键盘高度 = WebView 底部（屏幕坐标）与键盘顶边（keyboardFrameEndUserInfoKey）的重叠量；
 *   - autoAvoid：把该高度加到 webView.scrollView.contentInset.bottom（iOS 标准键盘避让做法，
 *     不破坏 WebView 布局），关闭时还原为进入前的 inset；
 *   - iOS 的 point 单位 = CSS px，所以 height 直接就是 CSS px；物理像素 = × scale。
 *
 * 监听/解除监听由 Capacitor 桥的 JS 侧 addListener/removeListener 处理。
 */
@objc(KeyboardAvoidingPlugin)
public class KeyboardAvoidingPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "KeyboardAvoidingPlugin"
    public let jsName = "KeyboardAvoiding"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "enable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getState", returnType: CAPPluginReturnPromise)
    ]

    private var autoAvoid = false
    private var keyboardVisible = false
    private var keyboardHeightCss: Double = 0
    private var animationDuration: Double = 0

    /// 自动避让前的 contentInset（用于 disable 时还原）。
    private var baselineContentInset: UIEdgeInsets?

    // ---------------------------------------------------------------- 生命周期

    override public func load() {
        super.load()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(keyboardWillChangeFrame(_:)),
            name: UIResponder.keyboardWillChangeFrameNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(keyboardWillHide(_:)),
            name: UIResponder.keyboardWillHideNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // ---------------------------------------------------------------- API

    @objc func enable(_ call: CAPPluginCall) {
        setAutoAvoid(true, call)
    }

    @objc func disable(_ call: CAPPluginCall) {
        setAutoAvoid(false, call)
    }

    @objc func setEnabled(_ call: CAPPluginCall) {
        setAutoAvoid(call.getBool("enabled", false), call)
    }

    @objc func isEnabled(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            call.resolve(self.stateDict())
        }
    }

    @objc func getState(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            call.resolve(self.stateDict())
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private func setAutoAvoid(_ enabled: Bool, _ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.autoAvoid = enabled
            if enabled {
                self.applyAutoAvoid(self.keyboardHeightCss)
            } else {
                self.restoreBaselineInset()
            }
            call.resolve(self.stateDict())
        }
    }

    @objc private func keyboardWillChangeFrame(_ note: Notification) {
        guard let endFrame = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue else {
            return
        }
        let duration = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25

        DispatchQueue.main.async {
            self.animationDuration = duration
            guard let webView = self.bridge?.webView else {
                return
            }
            // webView 在屏幕坐标系中的底部
            let webBottom = webView.convert(webView.bounds, to: nil).maxY
            let overlap = max(0, webBottom - endFrame.minY)

            let changed = (overlap > 0) != self.keyboardVisible || abs(overlap - self.keyboardHeightCss) > 0.5
            self.keyboardVisible = overlap > 0
            self.keyboardHeightCss = overlap

            if self.autoAvoid {
                self.applyAutoAvoid(overlap)
            }
            if changed {
                self.notifyListeners("keyboardChanged", data: self.stateDict())
            }
        }
    }

    @objc private func keyboardWillHide(_ note: Notification) {
        let duration = (note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25
        DispatchQueue.main.async {
            let changed = self.keyboardVisible || self.keyboardHeightCss > 0
            self.animationDuration = duration
            self.keyboardVisible = false
            self.keyboardHeightCss = 0
            if self.autoAvoid {
                self.applyAutoAvoid(0)
            }
            if changed {
                self.notifyListeners("keyboardChanged", data: self.stateDict())
            }
        }
    }

    /// 把键盘高度应用到 WebView 的底部 inset（iOS 标准键盘避让做法）。
    private func applyAutoAvoid(_ heightCss: Double) {
        guard let webView = self.bridge?.webView else {
            return
        }
        let scrollView = webView.scrollView
        if baselineContentInset == nil {
            baselineContentInset = scrollView.contentInset
        }
        let base = baselineContentInset ?? .zero
        let inset = UIEdgeInsets(
            top: base.top,
            left: base.left,
            bottom: base.bottom + CGFloat(heightCss),
            right: base.right
        )
        scrollView.contentInset = inset
        scrollView.scrollIndicatorInsets = inset
    }

    private func restoreBaselineInset() {
        guard let webView = self.bridge?.webView, let base = baselineContentInset else {
            return
        }
        webView.scrollView.contentInset = base
        webView.scrollView.scrollIndicatorInsets = base
    }

    private func stateDict() -> [String: Any] {
        let webView = self.bridge?.webView
        let scale = Double(webView?.window?.screen.scale ?? UIScreen.main.scale)
        let webViewHeightCss = webView.map { Double($0.bounds.height) } ?? 0
        // 可用高度 = WebView 高度 - 键盘遮挡高度（与 Android 侧 viewportHeight 语义对齐）
        let usableCss = max(0, webViewHeightCss - keyboardHeightCss)
        return [
            "visible": keyboardVisible,
            "height": keyboardHeightCss,
            "heightPx": keyboardHeightCss * scale,
            "viewportHeight": usableCss,
            "viewportHeightPx": usableCss * scale,
            "autoAvoid": autoAvoid,
            "animationDuration": animationDuration * 1000
        ]
    }
}
