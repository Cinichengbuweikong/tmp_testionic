import Foundation
import Capacitor
import CapISHBridge

/**
 * iOS 侧的 capacitor-linux：引擎是 **iSH-ARM64**（AArch64 用户态 Linux 模拟器）。
 *
 * 现状（第 1 步）：
 * - `ISHShellExecutor` 提供的是**命令级**执行：`executeCommand:lineCallback:completion:`
 *   逐行回调（stdout / stderr 分开）+ 退出码 + pid，正好映射到 `exec()` 与
 *   `consoleOutput` 事件；
 * - 它**没有**交互式 stdin（没有长驻 shell + 写 stdin 的接口），所以 `write()` /
 *   `sendKey()` 这一步先明确拒绝，下一步用 iSH 的 real tty（`fs/dev.c` 的 tty 设备）
 *   或 `openpty` 接一个长驻 shell；
 * - 引擎本身要用 `scripts/fetch-ish-ios-runtime.sh` 在 macOS 上构建成 xcframework
 *   并链接进宿主 App；没链接时 `CapISHBridge` 是桩，这里会返回明确错误。
 *
 * 与 Android 的差异要如实反映在 `getCapabilities()` 里：隔离级别是 `none`
 * （iSH 的客户机跑在 App 进程内），不是 `process`。
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

    /// 没有交互式 stdin 的统一说明（下一步用 real tty 实现）。
    private static let noStdinReason =
        "iOS 暂不支持交互式 stdin：iSH-ARM64 目前只暴露命令级执行（ISHShellExecutor），" +
        "下一步要接 iSH 的 real tty / openpty 才能做长驻 shell 的 write()。现在请用 exec()。"

    private var engineReady = false
    private var state = "stopped"
    private var startedAt: Date?
    private var runningPids: Set<Int32> = []
    private var counter = 0

    // ------------------------------------------------------------ capabilities

    @objc func getCapabilities(_ call: CAPPluginCall) {
        let available = capish_available() != 0
        var reasons: [String] = []
        if !available {
            reasons.append(String(cString: capish_unavailable_reason()))
        }
        call.resolve([
            "platform": "ios",
            "supported": available,
            "engine": available ? "ish-arm64" : "none",
            "architecture": "aarch64",
            "nativeExecution": false,
            "isolation": "none",
            "apkAvailable": available,
            "persistence": "none",
            "minimum": [
                "iosVersion": "15.0"
            ],
            "reasons": reasons
        ])
    }

    @objc func getInfo(_ call: CAPPluginCall) {
        let available = capish_available() != 0
        call.resolve([
            "supported": available,
            "distribution": "alpine",
            "engine": available ? "ish-arm64" : "none",
            "architecture": "aarch64",
            "rootfsMode": "disk",
            "nativeExecution": false,
            "apkAvailable": available,
            "executable": "ISHShellExecutor",
            "executablePresent": available,
            "runtimeExtracted": available,
            "runtimeBundled": available,
            "runtimeError": available ? "" : String(cString: capish_unavailable_reason()),
            "state": state
        ])
    }

    @objc func getStatus(_ call: CAPPluginCall) {
        var payload: [String: Any] = [
            "state": state,
            "running": state == "running",
            "memoryMb": 0,
            "cpus": 0,
            "consoleTty": "",
            "stderrTty": "",
            "persistence": "none"
        ]
        if let startedAt = startedAt {
            payload["startedAt"] = Int(startedAt.timeIntervalSince1970 * 1000)
            payload["uptimeMs"] = Int(Date().timeIntervalSince(startedAt) * 1000)
        }
        if capish_available() == 0 {
            payload["lastError"] = String(cString: capish_unavailable_reason())
        }
        call.resolve(payload)
    }

    // --------------------------------------------------------------- lifecycle

    @objc func start(_ call: CAPPluginCall) {
        guard capish_available() != 0 else {
            call.reject(String(cString: capish_unavailable_reason()), "ENGINE_MISSING")
            return
        }
        engineReady = true
        state = "running"
        startedAt = Date()
        call.resolve([
            "state": state,
            "running": true,
            "memoryMb": 0,
            "cpus": 0,
            "consoleTty": "",
            "stderrTty": "",
            "persistence": "none",
            "booted": true
        ])
    }

    @objc func stop(_ call: CAPPluginCall) {
        // iSH 不需要"关机"：把还在跑的客户机进程杀掉即可。
        for pid in runningPids {
            _ = capish_kill(pid, 9)
        }
        runningPids.removeAll()
        engineReady = false
        state = "stopped"
        startedAt = nil
        call.resolve(["state": state, "running": false])
    }

    @objc func restart(_ call: CAPPluginCall) {
        for pid in runningPids {
            _ = capish_kill(pid, 9)
        }
        runningPids.removeAll()
        engineReady = capish_available() != 0
        state = engineReady ? "running" : "stopped"
        startedAt = engineReady ? Date() : nil
        call.resolve(["state": state, "running": engineReady])
    }

    // ------------------------------------------------------------------- stdin

    @objc func write(_ call: CAPPluginCall) {
        call.reject(LinuxPlugin.noStdinReason, "UNSUPPORTED_ON_IOS")
    }

    @objc func sendKey(_ call: CAPPluginCall) {
        call.reject(LinuxPlugin.noStdinReason, "UNSUPPORTED_ON_IOS")
    }

    // ------------------------------------------------------------------- share

    @objc func getShareDir(_ call: CAPPluginCall) {
        // iSH 的客户机是进程内的，宿主目录通过它的 bind mount 映射；这一步先返回 App 的
        // Documents 目录（用户可在"文件"App 里看到），等接入 real tty / bind mount 后再补 guestPath。
        let documents = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first ?? ""
        call.resolve([
            "hostPath": documents,
            "guestPath": "",
            "exists": FileManager.default.fileExists(atPath: documents),
            "available": capish_available() != 0,
            "note": "iOS 侧共享目录待接入 iSH 的 bind mount；现在可用宿主 Documents 目录"
        ])
    }

    // -------------------------------------------------------------------- exec

    @objc func exec(_ call: CAPPluginCall) {
        guard let command = call.getString("command"), !command.isEmpty else {
            call.reject("INVALID_ARGUMENT: command is required", "INVALID_ARGUMENT")
            return
        }
        guard capish_available() != 0 else {
            call.reject(String(cString: capish_unavailable_reason()), "ENGINE_MISSING")
            return
        }
        let timeoutMs = max(1000, call.getInt("timeoutMs", 30000))
        counter += 1
        let box = IshExecBox(plugin: self, call: call, timeoutMs: timeoutMs)
        // 看门狗必须在调用 capish_exec **之前**武装：客户机内核没 boot 时
        // become_new_init_child/do_execve 可能直接卡住不返回，那时就轮不到 pid>0 分支了
        // （实测：模拟器里 autotest 就是因此拿不到结果）。
        box.armWatchdog()
        let user = Unmanaged.passRetained(box).toOpaque()
        // 必须放到后台队列：客户机内核没 boot 时 `become_new_init_child()` 可能**永久阻塞**
        // （实测：模拟器里 WebView 整个冻住，autotest 连结果都打不出来）。
        // 放后台后最坏情况是看门狗到点 resolve `{timedOut:true}`，UI/JS 不会死。
        DispatchQueue.global(qos: .userInitiated).async {
            let pid = capish_exec(command, ishLineTrampoline, ishDoneTrampoline, user)
            guard pid > 0 else { return }   // pid<0：C 侧已经回调过 done
            box.pid = pid
            DispatchQueue.main.async { [weak self] in
                self?.runningPids.insert(pid)
            }
        }
    }

    fileprivate func emit(line: String, isStderr: Bool) {
        // Capacitor 8 的 ObjC 方法导入到 Swift 后要求 `data:` 标签。
        notifyListeners("consoleOutput", data: [
            "data": line + "\n",
            "stream": isStderr ? "stderr" : "stdout"
        ])
    }

    fileprivate func finish(pid: Int32) {
        runningPids.remove(pid)
    }
}

/// 一次 exec 的状态：C 回调不能捕获上下文，所以用它当 `user` 指针。
final class IshExecBox {
    let plugin: LinuxPlugin
    let call: CAPPluginCall
    let timeoutMs: Int
    let startedAt = Date()
    var stdout = ""
    var stderr = ""
    var pid: Int32 = 0
    var finished = false
    private var watchdog: DispatchWorkItem?

    init(plugin: LinuxPlugin, call: CAPPluginCall, timeoutMs: Int) {
        self.plugin = plugin
        self.call = call
        self.timeoutMs = timeoutMs
    }

    /// 超时看门狗：iSH 的 completion 只有在客户机进程真的退出时才回调，引擎没装 rootfs
    /// 时可能永远不回调 —— 那样 JS 侧就会一直等。这里到点就 SIGKILL 并 resolve
    /// (`timedOut: true`)，语义与 Android 侧一致。
    func armWatchdog() {
        let item = DispatchWorkItem { [weak self] in
            guard let self = self, !self.finished else { return }
            capish_kill(self.pid, 9)
            self.complete(code: -3, duration: Date().timeIntervalSince(self.startedAt))
        }
        watchdog = item
        DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(timeoutMs), execute: item)
    }

    func cancelWatchdog() {
        watchdog?.cancel()
        watchdog = nil
    }

    func append(line: String, isStderr: Bool) {
        if isStderr {
            stderr += line + "\n"
        } else {
            stdout += line + "\n"
        }
        plugin.emit(line: line, isStderr: isStderr)
    }

    /// 必须只执行一次（C 侧保证 done 只回调一次；这是额外保险）。
    func complete(code: Int32, duration: Double) {
        if finished { return }
        finished = true
        cancelWatchdog()
        plugin.finish(pid: pid)
        var payload: [String: Any] = [
            "code": Int(code),
            "stdout": stdout,
            "stderr": stderr,
            "durationMs": Int((duration > 0 ? duration : Date().timeIntervalSince(startedAt)) * 1000),
            "timedOut": false,
            "interrupted": false
        ]
        if code == -3 {
            payload["timedOut"] = true
        }
        call.resolve(payload)
    }
}

/// C -> Swift 的逐行回调（全局函数指针，不能捕获上下文）。
private let ishLineTrampoline: @convention(c) (UnsafePointer<CChar>?, Int32, UnsafeMutableRawPointer?) -> Void = {
    cLine, isStderr, user in
    guard let user = user else { return }
    guard let cLine = cLine else { return }
    let box = Unmanaged<IshExecBox>.fromOpaque(user).takeUnretainedValue()
    box.append(line: String(cString: cLine), isStderr: isStderr != 0)
}

/// C -> Swift 的完成回调。
private let ishDoneTrampoline: @convention(c) (Int32, Int32, Double, UnsafeMutableRawPointer?) -> Void = {
    code, _pid, duration, user in
    guard let user = user else { return }
    let box = Unmanaged<IshExecBox>.fromOpaque(user).takeUnretainedValue()
    box.complete(code: code, duration: duration)
    // 与 passRetained 配对；C 侧保证 done 只会被调用一次。
    Unmanaged<IshExecBox>.fromOpaque(user).release()
}
