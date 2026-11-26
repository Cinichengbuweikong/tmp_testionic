import Capacitor
import Foundation
import UIKit
import WebKit

/**
 * Python 插件的 iOS 原生壳。
 *
 * Python 引擎是 Pyodide（CPython → WebAssembly），运行在 WKWebView 的 Web Worker 里
 * （Capacitor 8 的 `capacitor://localhost` 已经能正确返回 `.wasm` 的 MIME，Worker 也支持 ESM），
 * 因此会话、stdio、pip 逻辑都在 JS 侧。原生壳只做两件事：
 *   1. 上报系统版本，供 JS 判断是否满足 Pyodide 的最低要求（iOS 16.4+）；
 *   2. 预留未来切到"原生 CPython"（Python-Apple-support / xcframework）的挂载点。
 *
 * 为什么不用原生 CPython 作为默认引擎，见 README「为什么是 WebAssembly 版 CPython」：
 * iOS 禁止 fork/exec/子进程，进程内嵌 CPython 一旦段错误就是整个 App 崩溃，
 * 无法满足"Python 崩溃不影响主程序"的要求；Web Worker 则是真正独立的线程/进程边界。
 */
@objc(PythonPlugin)
public class PythonPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "PythonPlugin"
    public let jsName = "Python"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getInfo", returnType: CAPPluginReturnPromise)
    ]

    /// Pyodide 314.x 官方要求 iOS / WKWebView ≥ 16.4（更低的 WebKit 解析不了它的 ESM/新语法）。
    private let minimumIOS = "16.4"

    @objc public func getInfo(_ call: CAPPluginCall) {
        let systemVersion = UIDevice.current.systemVersion
        let supported = compareVersions(systemVersion, minimumIOS) >= 0
        var result: [String: Any] = [
            "platform": "ios",
            "engine": "pyodide",
            "systemVersion": systemVersion,
            "webViewVersion": systemVersion,
            "supported": supported
        ]
        if !supported {
            result["note"] = "iOS \(systemVersion) 低于 Pyodide 要求的 \(minimumIOS)，Python 运行时可能无法加载。"
        }
        call.resolve(result)
    }

    /// 语义化比较（只处理数字段，缺省补 0），返回 -1/0/1。
    private func compareVersions(_ lhs: String, _ rhs: String) -> Int {
        let left = lhs.split(separator: ".").map { Int($0) ?? 0 }
        let right = rhs.split(separator: ".").map { Int($0) ?? 0 }
        let count = max(left.count, right.count)
        for index in 0..<count {
            let l = index < left.count ? left[index] : 0
            let r = index < right.count ? right[index] : 0
            if l != r { return l < r ? -1 : 1 }
        }
        return 0
    }
}
