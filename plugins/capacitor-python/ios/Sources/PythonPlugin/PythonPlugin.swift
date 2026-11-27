import Capacitor
import Foundation
import UIKit

/**
 * capacitor-python 的 iOS 实现：**原生 CPython**（BeeWare Python-Apple-support 静态库）。
 *
 * 与 Android 对齐：同一个解释器、会话 = 独立 globals + 阻塞 stdin、stdout/stderr 实时事件。
 * 引擎实现见 `PythonEngineIOS.swift`（Py_* C API + PythonShim）。
 *
 * 注意：C 扩展（ssl/sqlite3 等）需要宿主 App 的 Run Script 把 `lib-dynload` 转成 framework
 * 并签名；当前只打包纯 Python stdlib + 内建模块（见 README「iOS 原生引擎」）。
 */
@objc(PythonPlugin)
public class PythonPlugin: CAPPlugin, CAPBridgedPlugin, PythonEngineDelegate {
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

    private let engine = PythonEngineIOS.shared
    private var sequence = 0

    override public func load() {
        engine.delegate = self
    }

    override public func handleOnDestroy() {
        engine.terminateAll()
    }

    // MARK: - 输出事件

    public func pythonDidEmit(sessionId: String, stream: String, text: String) {
        notifyListeners("sessionOutput", data: [
            "sessionId": sessionId,
            "stream": stream,
            "data": text
        ])
    }

    // MARK: - 基础信息

    @objc public func getInfo(_ call: CAPPluginCall) {
        var result: [String: Any] = [
            "platform": "ios",
            "engine": "native",
            "systemVersion": UIDevice.current.systemVersion,
            "supported": true
        ]
        if engine.isStarted {
            result["pythonVersion"] = engine.pythonVersion
        }
        call.resolve(result)
    }

    @objc public func initialize(_ call: CAPPluginCall) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let version = try self.engine.start()
                call.resolve([
                    "engine": "native",
                    "pythonVersion": version,
                    "platform": "ios",
                    "systemVersion": UIDevice.current.systemVersion
                ])
            } catch {
                call.reject("PYTHON_INIT_FAILED: \(error.localizedDescription)", "PYTHON_INIT_FAILED", error)
            }
        }
    }

    // MARK: - 会话

    @objc public func createSession(_ call: CAPPluginCall) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let session = try self.engine.createSession(name: call.getString("name") ?? "session")
                call.resolve(self.info(session))
            } catch {
                call.reject("CREATE_SESSION_FAILED: \(error.localizedDescription)", "CREATE_SESSION_FAILED", error)
            }
        }
    }

    @objc public func listSessions(_ call: CAPPluginCall) {
        call.resolve(["sessions": engine.allSessions().map { info($0) }])
    }

    @objc public func terminateSession(_ call: CAPPluginCall) {
        guard let sessionId = call.getString("sessionId") else {
            call.reject("sessionId is required")
            return
        }
        engine.terminate(id: sessionId)
        call.resolve(["terminated": true])
    }

    @objc public func terminateAll(_ call: CAPPluginCall) {
        engine.terminateAll()
        call.resolve(["terminated": true])
    }

    @objc public func restartSession(_ call: CAPPluginCall) {
        guard let sessionId = call.getString("sessionId") else {
            call.reject("sessionId is required")
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            let name = self.engine.session(id: sessionId)?.name ?? "session"
            self.engine.terminate(id: sessionId)
            do {
                let session = try self.engine.createSession(name: name)
                call.resolve(self.info(session))
            } catch {
                call.reject("RESTART_FAILED: \(error.localizedDescription)", "RESTART_FAILED", error)
            }
        }
    }

    // MARK: - 执行

    @objc public func exec(_ call: CAPPluginCall) {
        guard let sessionId = call.getString("sessionId") else {
            call.reject("sessionId is required")
            return
        }
        guard engine.session(id: sessionId) != nil else {
            call.reject("SESSION_NOT_FOUND: \(sessionId)", "SESSION_NOT_FOUND")
            return
        }
        let code = call.getString("code") ?? ""
        let executionId = nextExecutionId("exec")
        engine.run(sessionId: sessionId, source: code) { json in
            let result = Self.parse(json)
            if result.ok {
                call.resolve(["executionId": executionId, "result": result.value])
            } else {
                let data: JSObject = [
                    "executionId": executionId,
                    "type": result.type,
                    "traceback": result.traceback ?? ""
                ]
                call.reject("\(result.type): \(result.message)", result.type, nil, data)
            }
        }
    }

    @objc public func run(_ call: CAPPluginCall) {
        guard let sessionId = call.getString("sessionId") else {
            call.reject("sessionId is required")
            return
        }
        let code = call.getString("code") ?? ""
        let executionId = nextExecutionId("run")
        call.resolve(["executionId": executionId])
        engine.run(sessionId: sessionId, source: code) { json in
            let result = Self.parse(json)
            if result.ok {
                self.notifyListeners("sessionDone", data: [
                    "sessionId": sessionId,
                    "executionId": executionId,
                    "result": result.value
                ])
            } else {
                self.notifyListeners("sessionError", data: [
                    "sessionId": sessionId,
                    "executionId": executionId,
                    "type": result.type,
                    "message": result.message,
                    "traceback": result.traceback ?? ""
                ])
            }
        }
    }

    @objc public func writeStdin(_ call: CAPPluginCall) {
        guard let sessionId = call.getString("sessionId") else {
            call.reject("sessionId is required")
            return
        }
        engine.pushStdin(sessionId: sessionId, line: call.getString("data") ?? "")
        call.resolve(["ok": true])
    }

    // MARK: - 包（纯 Python wheel：下载 → zipfile 解压 → sys.path）

    @objc public func installPackages(_ call: CAPPluginCall) {
        guard let sessionId = call.getString("sessionId"),
              let packages = call.getArray("packages") as? [String], !packages.isEmpty else {
            call.reject("sessionId / packages is required")
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let target = try self.packagesDirectory()
                for entry in packages {
                    let wheel = try self.localWheel(entry)
                    let script = [
                        "import zipfile, sys",
                        "zipfile.ZipFile(r'\(wheel.path)').extractall(r'\(target.path)')",
                        "if r'\(target.path)' not in sys.path: sys.path.append(r'\(target.path)')"
                    ].joined(separator: "\n")
                    let json = self.engine.runSync(sessionId: sessionId, source: script)
                    guard Self.parse(json).ok else {
                        throw NSError(domain: "capacitor-python", code: 10, userInfo: [
                            NSLocalizedDescriptionKey: "INSTALL_FAILED: \(entry)"
                        ])
                    }
                }
                call.resolve(["installed": packages])
            } catch {
                call.reject("INSTALL_FAILED: \(error.localizedDescription)", "INSTALL_FAILED", error)
            }
        }
    }

    // MARK: - helpers

    private func nextExecutionId(_ prefix: String) -> String {
        sequence += 1
        return "\(prefix)-\(sequence)"
    }

    private func info(_ session: PythonEngineIOS.Session) -> [String: Any] {
        var result: [String: Any] = [
            "sessionId": session.id,
            "name": session.name,
            "state": session.state,
            "stdinMode": "blocking",
            "createdAt": Int(session.createdAt.timeIntervalSince1970 * 1000)
        ]
        if engine.isStarted {
            result["pythonVersion"] = engine.pythonVersion
        }
        return result
    }

    private func packagesDirectory() throws -> URL {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("python-packages", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// 支持 http(s) URL 与本地路径；返回下载/复制后的本地文件。
    private func localWheel(_ entry: String) throws -> URL {
        if let url = URL(string: entry), let scheme = url.scheme, scheme == "http" || scheme == "https" {
            let data = try Data(contentsOf: url)
            let temp = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString + ".whl")
            try data.write(to: temp)
            return temp
        }
        let path = URL(fileURLWithPath: entry)
        guard FileManager.default.fileExists(atPath: path.path) else {
            throw NSError(domain: "capacitor-python", code: 11, userInfo: [
                NSLocalizedDescriptionKey: "WHEEL_NOT_FOUND: \(entry)"
            ])
        }
        return path
    }

    struct RunResult {
        let ok: Bool
        let value: Any
        let type: String
        let message: String
        let traceback: String?
    }

    /// 解析 Python 侧返回的 JSON。
    static func parse(_ json: String) -> RunResult {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return RunResult(ok: false, value: NSNull(), type: "Error", message: "invalid result json", traceback: json)
        }
        let ok = object["ok"] as? Bool ?? false
        let value: Any = object["result"] ?? NSNull()
        let type = object["type"] as? String ?? "Error"
        let message = object["message"] as? String ?? ""
        let traceback = object["traceback"] as? String
        return RunResult(ok: ok, value: value, type: type, message: message, traceback: traceback)
    }
}
