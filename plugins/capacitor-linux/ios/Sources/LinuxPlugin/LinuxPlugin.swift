import Foundation
import Capacitor

/**
 * iOS 侧的 capacitor-linux。
 *
 * 为什么现在不支持（而不是"没做"）：
 * - Apple 不允许 JIT，也不允许 fork/exec 子进程（`An iOS app cannot use any form
 *   of subprocessing`），所以 QEMU 的 TCG JIT、chroot/proot 在 iOS 上都不成立；
 * - 能跑真 Linux 的成熟方案只有两类：
 *   1) 解释器级内核（iSH：x86 解释器 + 自己实现 Linux 系统调用，App Store 上架多年）；
 *   2) QEMU 的 TCI 解释器（UTM SE）。
 *   两者都需要把一个几十万行 C/C++ 的工程编成 iOS framework，且必须在 macOS 上构建；
 *   本仓库目前没有 Mac，只能在 CI 上迭代，属于独立的一个大工程（见 README「iOS 路线」）。
 *
 * 这一版把 API 面完整实现（返回明确错误 + 能力描述），这样 JS 侧代码在两端可以
 * 完全一致，将来换引擎只改这个文件。
 */
@objc(LinuxPlugin)
public class LinuxPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "LinuxPlugin"
    public let jsName = "Linux"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getCapabilities", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "restart", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "write", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sendKey", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getShareDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "exec", returnType: CAPPluginReturnPromise)
    ]

    private static let reason =
        "iOS 不支持：Apple 禁止 JIT 和 fork/exec 子进程，跑真 Linux 需要移植解释器级内核" +
        "（iSH 或 QEMU TCI），这需要 macOS 构建环境，属于独立的后续版本。Android 侧可用。"

    @objc func getCapabilities(_ call: CAPPluginCall) {
        call.resolve([
            "platform": "ios",
            "supported": false,
            "engine": "none",
            "architecture": "unknown",
            "nativeExecution": false,
            "isolation": "none",
            "apkAvailable": false,
            "persistence": "none",
            "minimum": [
                "iosVersion": "15.0"
            ],
            "reasons": [LinuxPlugin.reason]
        ])
    }

    @objc func getInfo(_ call: CAPPluginCall) {
        call.resolve([
            "supported": false,
            "distribution": "alpine",
            "engine": "none",
            "architecture": "unknown",
            "rootfsMode": "initramfs",
            "nativeExecution": false,
            "apkAvailable": false,
            "executable": "",
            "executablePresent": false,
            "runtimeExtracted": false,
            "runtimeBundled": false,
            "runtimeError": LinuxPlugin.reason,
            "state": "stopped"
        ])
    }

    @objc func getStatus(_ call: CAPPluginCall) {
        call.resolve([
            "state": "stopped",
            "running": false,
            "memoryMb": 0,
            "cpus": 0,
            "consoleTty": "",
            "stderrTty": "",
            "lastError": LinuxPlugin.reason
        ])
    }

    @objc func start(_ call: CAPPluginCall) {
        call.reject(LinuxPlugin.reason, "UNSUPPORTED_PLATFORM")
    }

    @objc func stop(_ call: CAPPluginCall) {
        call.reject(LinuxPlugin.reason, "UNSUPPORTED_PLATFORM")
    }

    @objc func restart(_ call: CAPPluginCall) {
        call.reject(LinuxPlugin.reason, "UNSUPPORTED_PLATFORM")
    }

    @objc func write(_ call: CAPPluginCall) {
        call.reject(LinuxPlugin.reason, "UNSUPPORTED_PLATFORM")
    }

    @objc func sendKey(_ call: CAPPluginCall) {
        call.reject(LinuxPlugin.reason, "UNSUPPORTED_PLATFORM")
    }

    @objc func getShareDir(_ call: CAPPluginCall) {
        call.resolve([
            "hostPath": "",
            "guestPath": "/mnt/host",
            "exists": false,
            "available": false,
            "note": LinuxPlugin.reason
        ])
    }

    @objc func exec(_ call: CAPPluginCall) {
        call.reject(LinuxPlugin.reason, "UNSUPPORTED_PLATFORM")
    }
}
