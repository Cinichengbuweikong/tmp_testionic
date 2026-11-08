import Foundation
import UIKit
import Capacitor

/**
 * Fullscreen 插件（iOS 侧，jsName = "Fullscreen"）。
 *
 * 实现要点：
 * 1. 状态栏：用 Capacitor 公开 API `bridge.statusBarVisible = false/true`
 *    （内部走 CAPBridgeViewController.setStatusBarVisible，会让 prefersStatusBarHidden 生效）；
 * 2. Home Indicator：iOS **不允许** App 直接隐藏，只能 `prefersHomeIndicatorAutoHidden`
 *    （自动淡出）。Capacitor 内置的 SystemBars 插件已 override 了这个属性，
 *    本插件通过调用它的 `hide:`/`show:`（@objc 方法，运行时检查存在性）来借用该能力；
 *    拿不到插件时自动降级为「只隐藏状态栏」，不影响其他功能；
 * 3. 状态栏隐藏后顶部安全区变 0，WebView 内容自然铺满；
 * 4. 只在状态真正变化时派发 fullscreenChanged。
 */
@objc(FullscreenPlugin)
public class FullscreenPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "FullscreenPlugin"
    public let jsName = "Fullscreen"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "enable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setFullscreen", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "toggle", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isFullscreen", returnType: CAPPluginReturnPromise)
    ]

    private var fullscreen = false

    // ---------------------------------------------------------------- API

    @objc func enable(_ call: CAPPluginCall) {
        apply(true, call)
    }

    @objc func disable(_ call: CAPPluginCall) {
        apply(false, call)
    }

    @objc func setFullscreen(_ call: CAPPluginCall) {
        apply(call.getBool("enabled", false), call)
    }

    @objc func toggle(_ call: CAPPluginCall) {
        apply(!fullscreen, call)
    }

    @objc func isFullscreen(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            call.resolve(self.stateDict())
        }
    }

    // ---------------------------------------------------------------- 内部

    private func apply(_ enabled: Bool, _ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let changed = self.fullscreen != enabled

            // 1) 状态栏（Capacitor 公开 API）
            self.bridge?.statusBarVisible = !enabled

            // 2) Home Indicator（借内置 SystemBars 插件的能力；拿不到就降级）
            self.toggleHomeIndicator(hidden: enabled)

            self.fullscreen = enabled
            let state = self.stateDict()
            if changed {
                self.notifyListeners("fullscreenChanged", data: state)
            }
            call.resolve(state)
        }
    }

    /**
     * 状态输出。
     * iOS 没有系统导航栏；Home Indicator 由系统控制显示时机（App 只能请求自动隐藏），
     * 因此 navigationBarHidden 与 fullscreen 同步。
     */
    private func stateDict() -> [String: Any] {
        [
            "fullscreen": fullscreen,
            "statusBarHidden": fullscreen,
            "navigationBarHidden": fullscreen
        ]
    }

    /// 通过内置 SystemBars 插件触发 Home Indicator 的自动隐藏/恢复。
    /// SystemBars 的 CAPBridgeViewController 扩展会读取它的状态来 override
    /// prefersHomeIndicatorAutoHidden；它的 hide:/show: 是 @objc 方法，可运行时调用。
    private func toggleHomeIndicator(hidden: Bool) {
        guard let plugin = self.bridge?.plugin(withName: "SystemBars") else {
            return
        }
        let selector = NSSelectorFromString(hidden ? "hide:" : "show:")
        guard plugin.responds(to: selector) else {
            return
        }
        let fakeCall = CAPPluginCall(
            callbackId: "fullscreen-home-indicator",
            methodName: hidden ? "hide" : "show",
            options: [:],
            success: { _, _ in },
            error: { _ in }
        )
        _ = plugin.perform(selector, with: fakeCall)
    }
}
