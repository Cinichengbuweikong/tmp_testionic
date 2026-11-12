import Capacitor
import UIKit

/**
 * 开机自启插件（iOS 侧）。
 *
 * iOS **不允许第三方 App 开机自启**：没有 BOOT_COMPLETED 之类的系统广播，
 * 后台能力（BGTaskScheduler、VoIP push、定位等）也不能在开机时自动启动 App。
 *
 * 因此这里只返回 `supported: false` + 原因，`enable()` 不会改变任何状态；
 * `openAppSettings()` 打开系统的应用设置页（用户可以手动调整后台刷新等）。
 */
@objc(BootAutostartPlugin)
public class BootAutostartPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "BootAutostartPlugin"
    public let jsName = "BootAutostart"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "enable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestNotificationPermission", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestIgnoreBatteryOptimizations", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openAppSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openAutostartSettings", returnType: CAPPluginReturnPromise)
    ]

    private func unsupported() -> [String: Any] {
        return [
            "supported": false,
            "enabled": false,
            "reason": "iOS 不允许第三方 App 开机自启"
        ]
    }

    @objc public func getStatus(_ call: CAPPluginCall) {
        call.resolve(unsupported())
    }

    @objc public func enable(_ call: CAPPluginCall) {
        call.resolve(unsupported())
    }

    @objc public func disable(_ call: CAPPluginCall) {
        call.resolve(unsupported())
    }

    @objc public func requestNotificationPermission(_ call: CAPPluginCall) {
        call.resolve(unsupported())
    }

    @objc public func requestIgnoreBatteryOptimizations(_ call: CAPPluginCall) {
        call.resolve(unsupported())
    }

    @objc public func openAppSettings(_ call: CAPPluginCall) {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            DispatchQueue.main.async {
                UIApplication.shared.open(url)
            }
        }
        call.resolve()
    }

    @objc public func openAutostartSettings(_ call: CAPPluginCall) {
        openAppSettings(call)
    }
}
