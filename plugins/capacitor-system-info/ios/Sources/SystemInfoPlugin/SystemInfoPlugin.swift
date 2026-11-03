import Foundation
import UIKit
import Network
import WebKit
import Capacitor
import os

/**
 * SystemInfo 插件（iOS 侧，jsName = "SystemInfo"）。
 *
 * 与 Android 侧保持同一份 API 契约（见 src/definitions.ts）。
 * 设计原则：每种信息一个独立 API；每个 API 只收集自己需要的数据，
 * 互不依赖，某个 API 因系统差异失败不影响其他 API。
 *
 * 平台差异说明：
 *   - iOS 没有"底部导航栏"，getNavigationBarInfo() 对应 Home Indicator 区域；
 *   - iOS 没有 WebView 版本号 API，getWebViewInfo().version 取自 UA 的
 *     Safari "Version/x.y"；packageName 固定为 "WKWebView"；
 *   - getBatteryInfo() 在模拟器上 level 为 -1；
 *   - getNetworkInfo() 用 NWPathMonitor 异步探测，1.5s 超时兜底。
 */
@objc(SystemInfoPlugin)
public class SystemInfoPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "SystemInfoPlugin"
    public let jsName = "SystemInfo"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getScreenInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getViewport", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSafeAreaInsets", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getStatusBarInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getNavigationBarInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCutoutInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSystemInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getWebViewInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getMemoryInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getStorageInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getBatteryInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getNetworkInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAppInfo", returnType: CAPPluginReturnPromise)
    ]

    // ---------------------------------------------------------------- 方法入口

    @objc func getScreenInfo(_ call: CAPPluginCall) {
        onMain(call) { self.resolveScreen(call) }
    }

    @objc func getViewport(_ call: CAPPluginCall) {
        onMain(call) { self.resolveViewport(call) }
    }

    @objc func getSafeAreaInsets(_ call: CAPPluginCall) {
        onMain(call) { self.resolveSafeArea(call) }
    }

    @objc func getStatusBarInfo(_ call: CAPPluginCall) {
        onMain(call) { self.resolveStatusBar(call) }
    }

    @objc func getNavigationBarInfo(_ call: CAPPluginCall) {
        onMain(call) { self.resolveNavigationBar(call) }
    }

    @objc func getCutoutInfo(_ call: CAPPluginCall) {
        onMain(call) { self.resolveCutout(call) }
    }

    @objc func getSystemInfo(_ call: CAPPluginCall) {
        onMain(call) { self.resolveSystem(call) }
    }

    @objc func getWebViewInfo(_ call: CAPPluginCall) {
        onMain(call) { self.resolveWebView(call) }
    }

    @objc func getMemoryInfo(_ call: CAPPluginCall) {
        onMain(call) { self.resolveMemory(call) }
    }

    @objc func getStorageInfo(_ call: CAPPluginCall) {
        onMain(call) { self.resolveStorage(call) }
    }

    @objc func getBatteryInfo(_ call: CAPPluginCall) {
        onMain(call) { self.resolveBattery(call) }
    }

    @objc func getNetworkInfo(_ call: CAPPluginCall) {
        onMain(call) { self.resolveNetwork(call) }
    }

    @objc func getAppInfo(_ call: CAPPluginCall) {
        onMain(call) { self.resolveApp(call) }
    }

    // ---------------------------------------------------------------- 收集实现

    /// 屏幕：分辨率 / CSS(pt) 尺寸 / 密度 / 刷新率 / 朝向。
    private func resolveScreen(_ call: CAPPluginCall) {
        let screen = UIScreen.main
        let scale = Double(screen.scale)
        let bounds = screen.bounds

        call.resolve([
            "px": ["width": Double(screen.nativeBounds.width), "height": Double(screen.nativeBounds.height)],
            "css": ["width": Double(bounds.width), "height": Double(bounds.height)],
            "orientation": bounds.width > bounds.height ? "landscape" : "portrait",
            "rotationDegrees": rotationDegrees(),
            "density": scale,
            "densityDpi": Int(scale * 160),
            "scaledDensity": scale,
            "refreshRate": refreshRate(screen)
        ])
    }

    /// WebView 视口（iOS 上 Capacitor 默认贴边满屏）。
    private func resolveViewport(_ call: CAPPluginCall) {
        let scale = Double(UIScreen.main.scale)
        var cssW = Double(UIScreen.main.bounds.width)
        var cssH = Double(UIScreen.main.bounds.height)
        if let webView = self.bridge?.webView {
            cssW = Double(webView.bounds.width)
            cssH = Double(webView.bounds.height)
        }
        call.resolve([
            "px": ["width": cssW * scale, "height": cssH * scale],
            "css": ["width": cssW, "height": cssH]
        ])
    }

    /// 安全区（pt ≈ CSS px）。
    private func resolveSafeArea(_ call: CAPPluginCall) {
        guard let window = self.bridge?.viewController?.view.window else {
            call.resolve(["top": 0, "right": 0, "bottom": 0, "left": 0])
            return
        }
        call.resolve([
            "top": Double(window.safeAreaInsets.top),
            "right": Double(window.safeAreaInsets.right),
            "bottom": Double(window.safeAreaInsets.bottom),
            "left": Double(window.safeAreaInsets.left)
        ])
    }

    /// 状态栏（宽 = 屏幕宽）。
    private func resolveStatusBar(_ call: CAPPluginCall) {
        let scale = Double(UIScreen.main.scale)
        let bounds = UIScreen.main.bounds
        let frame = self.bridge?.viewController?.view.window?.windowScene?.statusBarManager?.statusBarFrame
        let cssW = frame.map { Double($0.width) } ?? Double(bounds.width)
        let cssH = frame.map { Double($0.height) } ?? 0
        call.resolve([
            "px": ["width": cssW * scale, "height": cssH * scale],
            "css": ["width": cssW, "height": cssH],
            "visible": cssH > 0
        ])
    }

    /// 底部"导航栏"：iOS 上对应 Home Indicator。
    private func resolveNavigationBar(_ call: CAPPluginCall) {
        let scale = Double(UIScreen.main.scale)
        let widthCss = Double(UIScreen.main.bounds.width)
        let bottom = self.bridge?.viewController?.view.window.map { Double($0.safeAreaInsets.bottom) } ?? 0
        let hasIndicator = bottom > 0
        call.resolve([
            "px": ["width": hasIndicator ? widthCss * scale : 0, "height": bottom * scale],
            "css": ["width": hasIndicator ? widthCss : 0, "height": bottom],
            "visible": hasIndicator,
            "mode": hasIndicator ? "gesture" : "none"
        ])
    }

    /// 刘海：安全区顶部明显高于标准 20pt 状态栏即视为有（横屏时看左右）。
    private func resolveCutout(_ call: CAPPluginCall) {
        let window = self.bridge?.viewController?.view.window
        let top = window.map { Double($0.safeAreaInsets.top) } ?? 0
        let right = window.map { Double($0.safeAreaInsets.right) } ?? 0
        let bottom = window.map { Double($0.safeAreaInsets.bottom) } ?? 0
        let left = window.map { Double($0.safeAreaInsets.left) } ?? 0
        if top > 24 || left > 24 || right > 24 {
            call.resolve([
                "exists": true,
                "insetsCss": ["top": top, "right": right, "bottom": bottom, "left": left]
            ])
        } else {
            call.resolve(["exists": false])
        }
    }

    /// 系统与设备信息。
    private func resolveSystem(_ call: CAPPluginCall) {
        let device = UIDevice.current
        let model = hardwareModel()
        call.resolve([
            "platform": "ios",
            "osVersion": device.systemVersion,
            "manufacturer": "Apple",
            "brand": "Apple",
            "model": model,
            "deviceCode": model,
            "isEmulator": isEmulator(),
            "cpuAbis": cpuArchs(),
            "locale": Locale.current.identifier,
            "language": Locale.current.languageCode ?? "",
            "timezone": TimeZone.current.identifier,
            "is24Hour": is24HourFormat(),
            "fontScale": fontScale(),
            "darkMode": self.bridge?.viewController?.traitCollection.userInterfaceStyle == .dark
        ])
    }

    /// WebView 引擎：WKWebView 没有公开版本 API，从 UA 里取 Safari 版本。
    private func resolveWebView(_ call: CAPPluginCall) {
        var userAgent = ""
        if let webView = self.bridge?.webView as? WKWebView {
            userAgent = (webView.value(forKey: "userAgent") as? String) ?? ""
        }
        call.resolve([
            "packageName": "WKWebView",
            "version": safariVersion(from: userAgent),
            "userAgent": userAgent
        ])
    }

    /// 内存（字节）。
    private func resolveMemory(_ call: CAPPluginCall) {
        let total = ProcessInfo.processInfo.physicalMemory
        var available: Int64 = -1
        if #available(iOS 13.0, *) {
            available = Int64(os_proc_available_memory())
        }
        call.resolve(["totalBytes": total, "availableBytes": available])
    }

    /// 存储（数据分区，字节）。
    private func resolveStorage(_ call: CAPPluginCall) {
        var totalBytes: Int64 = -1
        var freeBytes: Int64 = -1
        let homeUrl = URL(fileURLWithPath: NSHomeDirectory())
        if let values = try? homeUrl.resourceValues(forKeys: [
            .volumeTotalCapacityKey,
            .volumeAvailableCapacityForImportantUsageKey
        ]) {
            totalBytes = Int64(values.volumeTotalCapacity ?? -1)
            freeBytes = Int64(values.volumeAvailableCapacityForImportantUsage ?? -1)
        }
        call.resolve(["totalBytes": totalBytes, "freeBytes": freeBytes])
    }

    /// 电量（模拟器上 level 为 -1）。
    private func resolveBattery(_ call: CAPPluginCall) {
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        let batteryLevel = device.batteryLevel
        device.isBatteryMonitoringEnabled = false
        let level = batteryLevel < 0 ? -1 : Int(batteryLevel * 100)
        let charging = device.batteryState == .charging || device.batteryState == .full
        call.resolve(["level": level, "charging": charging])
    }

    /// 网络：NWPathMonitor 首次回调即拿到当前网络；1.5s 超时兜底为 unknown。
    private func resolveNetwork(_ call: CAPPluginCall) {
        let monitor = NWPathMonitor()
        let queue = DispatchQueue(label: "capacitor.systeminfo.nwpath")
        var finished = false

        let done: (String, Bool, Bool) -> Void = { type, connected, metered in
            queue.async {
                if finished {
                    return
                }
                finished = true
                monitor.cancel()
                DispatchQueue.main.async {
                    call.resolve(["type": type, "connected": connected, "metered": metered])
                }
            }
        }

        monitor.pathUpdateHandler = { path in
            var type = "none"
            var connected = false
            var metered = false
            if path.status == .satisfied {
                connected = true
                metered = path.isExpensive
                if path.usesInterfaceType(.wifi) {
                    type = "wifi"
                } else if path.usesInterfaceType(.cellular) {
                    type = "cellular"
                } else if path.usesInterfaceType(.wiredEthernet) {
                    type = "ethernet"
                } else {
                    type = "unknown"
                }
            }
            done(type, connected, metered)
        }
        monitor.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 1.5) {
            done("unknown", false, false)
        }
    }

    /// 应用自身信息。
    private func resolveApp(_ call: CAPPluginCall) {
        let bundle = Bundle.main
        call.resolve([
            "id": bundle.bundleIdentifier ?? "",
            "name": (bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String) ?? "",
            "version": (bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "",
            "build": (bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String) ?? ""
        ])
    }

    // ---------------------------------------------------------------- 纯工具

    /// 统一主线程调度：body 已在主线程执行，负责 resolve 或 reject。
    private func onMain(_ call: CAPPluginCall, _ body: @escaping () -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                call.reject("plugin released")
                return
            }
            body()
        }
    }

    private func refreshRate(_ screen: UIScreen) -> Double {
        if #available(iOS 15.0, *) {
            return Double(screen.maximumFramesPerSecond)
        }
        return 60
    }

    private func rotationDegrees() -> Int {
        guard let orientation = self.bridge?.viewController?.view.window?.windowScene?.interfaceOrientation else {
            return 0
        }
        switch orientation {
        case .portrait: return 0
        case .portraitUpsideDown: return 180
        case .landscapeLeft: return 90
        case .landscapeRight: return 270
        default: return 0
        }
    }

    private func safariVersion(from userAgent: String) -> String {
        guard let range = userAgent.range(of: "Version/[0-9.]+", options: .regularExpression) else {
            return ""
        }
        return String(userAgent[range]).replacingOccurrences(of: "Version/", with: "")
    }

    private func cpuArchs() -> [String] {
        #if arch(arm64)
        return ["arm64"]
        #else
        return ["x86_64"]
        #endif
    }

    private func is24HourFormat() -> Bool {
        let format = DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: Locale.current) ?? ""
        return !format.contains("a")
    }

    /// iOS 动态字号（Dynamic Type）→ 近似 fontScale 映射。
    private func fontScale() -> Double {
        switch UIApplication.shared.preferredContentSizeCategory {
        case .extraSmall: return 0.82
        case .small: return 0.88
        case .medium: return 0.94
        case .large: return 1.0
        case .extraLarge: return 1.06
        case .extraExtraLarge: return 1.12
        case .extraExtraExtraLarge: return 1.18
        case .accessibilityMedium: return 1.24
        case .accessibilityLarge: return 1.3
        case .accessibilityExtraLarge: return 1.36
        case .accessibilityExtraExtraLarge: return 1.42
        case .accessibilityExtraExtraExtraLarge: return 1.48
        default: return 1.0
        }
    }

    /// 硬件型号（如 iPhone13,2），比 UIDevice.model 更精确。
    private func hardwareModel() -> String {
        var sysinfo = utsname()
        uname(&sysinfo)
        let mirror = Mirror(reflecting: sysinfo.machine)
        var model = ""
        for child in mirror.children {
            guard let value = child.value as? Int8, value != 0 else {
                break
            }
            model.append(Character(UnicodeScalar(UInt8(bitPattern: value))))
        }
        return model.isEmpty ? "unknown" : model
    }

    private func isEmulator() -> Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }
}
