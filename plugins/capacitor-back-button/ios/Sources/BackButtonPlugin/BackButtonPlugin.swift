import Foundation
import Capacitor

/**
 * 返回键插件（iOS 侧）。
 *
 * iOS 没有硬件返回键，所以这里不提供"拦截"能力（`enable/disable` 仅记录状态，
 * 供 JS 统一调用）；`canGoBack/goBack` 基于 WKWebView 的历史可用；
 * `exitApp` 会 reject（Apple 不允许第三方 App 主动退出）；
 * `minimizeApp` 无对应概念，直接 resolve。
 */
@objc(BackButtonPlugin)
public class BackButtonPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "BackButtonPlugin"
    public let jsName = "BackButton"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "enable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "canGoBack", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "goBack", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "exitApp", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "minimizeApp", returnType: CAPPluginReturnPromise)
    ]

    private var enabled = false

    @objc public func enable(_ call: CAPPluginCall) {
        enabled = true
        call.resolve(["enabled": true])
    }

    @objc public func disable(_ call: CAPPluginCall) {
        enabled = false
        call.resolve(["enabled": false])
    }

    @objc public func isEnabled(_ call: CAPPluginCall) {
        call.resolve(["enabled": enabled])
    }

    @objc public func canGoBack(_ call: CAPPluginCall) {
        call.resolve(["canGoBack": bridge?.webView?.canGoBack ?? false])
    }

    @objc public func goBack(_ call: CAPPluginCall) {
        guard let webView = bridge?.webView else {
            call.resolve(["wentBack": false])
            return
        }
        DispatchQueue.main.async {
            let wentBack = webView.canGoBack
            if wentBack {
                webView.goBack()
            }
            call.resolve(["wentBack": wentBack])
        }
    }

    @objc public func exitApp(_ call: CAPPluginCall) {
        call.reject("exitApp 在 iOS 上不可用：Apple 不允许第三方 App 主动退出")
    }

    @objc public func minimizeApp(_ call: CAPPluginCall) {
        call.resolve()
    }
}
