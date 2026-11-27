import Capacitor
import Foundation
import UIKit

/**
 * capacitor-python 的 iOS 原生壳。
 *
 * ## 当前状态（分步实现的第一步）
 *
 * iOS 上**原生 CPython 尚未接入**：Chaquopy 没有 iOS 版本，iOS 的原生等价物是
 * BeeWare 的 `Python-Apple-support`（CPython 3.11 静态库 + stdlib + `lib-dynload` 下的 .so 文件），
 * 它需要一个**宿主 App 的 Run Script** 把 67 个 `.so` 转成 `Frameworks` 下的 framework 并签名
 * （iOS 不允许 `Frameworks/` 之外的可执行代码），还会让安装包 +55MB —— 属于工程级改动。
 *
 * 因此目前 iOS 走 **Pyodide 引擎**（CPython 3.14 → WebAssembly，跑在 WKWebView 的 module worker 里，
 * CI 已验证）。`src/index.ts` 的门面只在 Android 上把调用转给原生插件，iOS/Web 都走 Pyodide。
 *
 * 下面这些方法是为了**方法数与 TS 接口保持一致**（仓库的 audit 会检查），
 * 同时给第二步（接入 Python-Apple-support）留好 API 面。第二步只需要替换
 * `PythonEngineIOS` 的实现，插件方法本身不用改。
 */
@objc(PythonPlugin)
public class PythonPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "PythonPlugin"
    public let jsName = "Python"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "initialize", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createSession", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "exec", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "run", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "writeStdin", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "listSessions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "terminateSession", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "terminateAll", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "restartSession", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "installPackages", returnType: CAPPluginReturnPromise)
    ]

    private static let notAvailableMessage =
        "NATIVE_ENGINE_NOT_AVAILABLE_ON_IOS: iOS 目前使用 Pyodide 引擎（WebAssembly），" +
        "原生 CPython 需要接入 Python-Apple-support（见 README「iOS 原生引擎」）。"

    // MARK: - 基础信息

    @objc public func getInfo(_ call: CAPPluginCall) {
        call.resolve([
            "platform": "ios",
            "engine": "pyodide",
            "systemVersion": UIDevice.current.systemVersion,
            "supported": true,
            "note": "iOS 使用 Pyodide（WebAssembly）引擎；原生 CPython 待接入（Python-Apple-support）"
        ])
    }

    // MARK: - 原生引擎接口（待实现）

    @objc public func initialize(_ call: CAPPluginCall) {
        call.reject(Self.notAvailableMessage, "NATIVE_ENGINE_NOT_AVAILABLE")
    }

    @objc public func createSession(_ call: CAPPluginCall) {
        call.reject(Self.notAvailableMessage, "NATIVE_ENGINE_NOT_AVAILABLE")
    }

    @objc public func exec(_ call: CAPPluginCall) {
        call.reject(Self.notAvailableMessage, "NATIVE_ENGINE_NOT_AVAILABLE")
    }

    @objc public func run(_ call: CAPPluginCall) {
        call.reject(Self.notAvailableMessage, "NATIVE_ENGINE_NOT_AVAILABLE")
    }

    @objc public func writeStdin(_ call: CAPPluginCall) {
        call.reject(Self.notAvailableMessage, "NATIVE_ENGINE_NOT_AVAILABLE")
    }

    @objc public func listSessions(_ call: CAPPluginCall) {
        call.reject(Self.notAvailableMessage, "NATIVE_ENGINE_NOT_AVAILABLE")
    }

    @objc public func terminateSession(_ call: CAPPluginCall) {
        call.reject(Self.notAvailableMessage, "NATIVE_ENGINE_NOT_AVAILABLE")
    }

    @objc public func terminateAll(_ call: CAPPluginCall) {
        call.reject(Self.notAvailableMessage, "NATIVE_ENGINE_NOT_AVAILABLE")
    }

    @objc public func restartSession(_ call: CAPPluginCall) {
        call.reject(Self.notAvailableMessage, "NATIVE_ENGINE_NOT_AVAILABLE")
    }

    @objc public func installPackages(_ call: CAPPluginCall) {
        call.reject(Self.notAvailableMessage, "NATIVE_ENGINE_NOT_AVAILABLE")
    }
}
