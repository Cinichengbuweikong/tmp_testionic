import Capacitor
import Foundation
import PythonShim

/**
 * iOS 原生 CPython 引擎（Python-Apple-support 静态库 + SPM 资源里的 stdlib）。
 *
 * 设计：
 *   - **一个解释器 / 一个 App**（和 Android 的 Chaquopy 一样）：会话 = 独立的 globals dict + 独立 stdin 队列；
 *   - 执行跑在并发队列上（CPython 的 GIL 负责串行 bytecode，阻塞的 stdin 读取在 C 层释放了 GIL）；
 *   - stdout/stderr：Python 侧 `capython.runtime` 的代理对象通过 ctypes 调 C shim 的回调，
 *     回调再转给 `delegate`（插件）→ Capacitor 的 `sessionOutput` 事件；
 *   - stdin：Python 的 `readline` 调 C 回调，C 回调在 Swift 里阻塞等待（NSCondition），
 *     最长 30s；超时按 EOF 处理。
 *
 * 已知边界（见 README）：
 *   - 没有进程隔离：段错误会带走 App；
 *   - 中断是协作式的（没有 PyThreadState_SetAsyncExc 的 Swift 入口）；
 *   - C 扩展（ssl/sqlite3 等）需要宿主 App 的 Run Script 把 lib-dynload 转成 framework 并签名，
 *     当前只打包了纯 Python stdlib + 内建模块。
 */
public protocol PythonEngineDelegate: AnyObject {
    func pythonDidEmit(sessionId: String, stream: String, text: String)
}

public final class PythonEngineIOS {

    public static let shared = PythonEngineIOS()

    public weak var delegate: PythonEngineDelegate?

    /// 一个会话的 Swift 侧状态。
    final class Session {
        let id: String
        let name: String
        let createdAt: Date
        var state: String = "idle"
        let condition = NSCondition()
        var pendingLines: [String] = []
        var closed = false

        init(id: String, name: String) {
            self.id = id
            self.name = name
            self.createdAt = Date()
        }
    }

    private let lock = NSLock()
    private var sessions: [String: Session] = [:]
    private let runQueue = DispatchQueue(label: "capacitor-python.run", qos: .userInitiated, attributes: .concurrent)
    private var started = false
    private var version = "unknown"
    private var lastError = ""

    private init() {}

    // MARK: - 生命周期

    /// 启动解释器（幂等）。返回 CPython 版本。
    func start() throws -> String {
        lock.lock()
        defer { lock.unlock() }
        if started {
            return version
        }
        // 优先用宿主 Run Script（BeeWare install_python）生成的 App.app/python：
        // 它包含 lib-dynload（C 扩展）和 app_packages；没有时退回 SPM 资源里裁剪过的纯 Python stdlib。
        let fileManager = FileManager.default
        let bundlePython = Bundle.main.bundleURL.appendingPathComponent("python", isDirectory: true)
        let modulePython = Bundle.module.resourceURL?.appendingPathComponent("python", isDirectory: true)
        let resourceURL: URL
        if fileManager.fileExists(atPath: bundlePython.appendingPathComponent("lib").path) {
            resourceURL = bundlePython
        } else if let modulePython, fileManager.fileExists(atPath: modulePython.path) {
            resourceURL = modulePython
        } else {
            throw NSError(domain: "capacitor-python", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "PYTHON_RESOURCES_MISSING: 找不到 App.app/python 或 SPM 资源 python/（先跑 fetch-python-ios-runtime.mjs）"
            ])
        }
        // 版本目录从 fetch 脚本生成的 PythonRuntime.json 读（升级 Python 只改脚本的 RELEASES 表）
        let series = Self.series(from: resourceURL)
        let stdlib = resourceURL.appendingPathComponent("lib/python\(series)", isDirectory: true).path
        guard fileManager.fileExists(atPath: stdlib) else {
            throw NSError(domain: "capacitor-python", code: 2, userInfo: [
                NSLocalizedDescriptionKey: "PYTHON_STDLIB_MISSING: \(stdlib)"
            ])
        }
        // sys.path：stdlib + site-packages + （可选）App.app/app_packages（C 扩展/C 轮子放在这里）
        var searchPaths = [stdlib, stdlib + "/site-packages"]
        let appPackages = Bundle.main.bundleURL.appendingPathComponent("app_packages", isDirectory: true)
        if fileManager.fileExists(atPath: appPackages.path) {
            searchPaths.append(appPackages.path)
        }
        let searchPath = searchPaths.filter { fileManager.fileExists(atPath: $0) }.joined(separator: ":")
        let runtimeURL = resourceURL.appendingPathComponent("capython/runtime.py")
        let runtimeSource: String
        do {
            runtimeSource = try String(contentsOf: runtimeURL, encoding: .utf8)
        } catch {
            throw NSError(domain: "capacitor-python", code: 3, userInfo: [
                NSLocalizedDescriptionKey: "PYTHON_RUNTIME_SOURCE_MISSING: \(runtimeURL.path)"
            ])
        }

        cp_set_output(pythonEmitBridge)
        cp_set_input(pythonReadBridge)

        let status: Int32 = resourceURL.path.withCString { home in
            searchPath.withCString { path in
                runtimeSource.withCString { src in
                    cp_init(home, path, src)
                }
            }
        }
        if status != 0 {
            lastError = String(cString: cp_last_error())
            throw NSError(domain: "capacitor-python", code: Int(status), userInfo: [
                NSLocalizedDescriptionKey: "PYTHON_INIT_FAILED: \(lastError)"
            ])
        }
        version = String(cString: cp_version())
        started = true
        return version
    }

    var isStarted: Bool {
        lock.lock()
        defer { lock.unlock() }
        return started
    }

    var pythonVersion: String {
        lock.lock()
        defer { lock.unlock() }
        return version
    }

    /// 从资源里的 PythonRuntime.json 读取 Python 版本系列（如 "3.11"），读不到时退回 "3.11"。
    private static func series(from resourceURL: URL) -> String {
        let metaURL = resourceURL.appendingPathComponent("PythonRuntime.json")
        guard let data = try? Data(contentsOf: metaURL),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let series = object["series"] as? String,
              !series.isEmpty else {
            return "3.11"
        }
        return series
    }

    // MARK: - 会话

    func createSession(name: String) throws -> Session {
        _ = try start()
        let id = "py-" + UUID().uuidString.prefix(8).lowercased()
        let session = Session(id: id, name: name.isEmpty ? "session" : name)
        lock.lock()
        sessions[id] = session
        lock.unlock()
        return session
    }

    func session(id: String) -> Session? {
        lock.lock()
        defer { lock.unlock() }
        return sessions[id]
    }

    func allSessions() -> [Session] {
        lock.lock()
        defer { lock.unlock() }
        return Array(sessions.values)
    }

    func terminate(id: String) {
        lock.lock()
        let session = sessions.removeValue(forKey: id)
        lock.unlock()
        session?.condition.lock()
        session?.closed = true
        session?.condition.broadcast()
        session?.condition.unlock()
    }

    func terminateAll() {
        for session in allSessions() {
            terminate(id: session.id)
        }
    }

    // MARK: - 执行

    /// 执行代码，返回 Python 侧给的 JSON 字符串（在调用线程上阻塞）。
    func runSync(sessionId: String, source: String) -> String {
        let json: UnsafeMutablePointer<CChar>? = source.withCString { src in
            sessionId.withCString { sid in
                cp_run(sid, src)
            }
        }
        guard let json else {
            return "{\"ok\":false,\"type\":\"Error\",\"message\":\"cp_run returned null\"}"
        }
        defer { free(json) }
        return String(cString: json)
    }

    /// 异步执行；completion 在 runQueue 上回调。
    func run(sessionId: String, source: String, completion: @escaping (String) -> Void) {
        runQueue.async {
            completion(self.runSync(sessionId: sessionId, source: source))
        }
    }

    // MARK: - stdin

    func pushStdin(sessionId: String, line: String) {
        guard let session = session(id: sessionId) else { return }
        session.condition.lock()
        // 上限 1000 行：防止脚本不消费输入时内存无限增长（丢最旧的，保留最近的）
        if session.pendingLines.count >= 1000 {
            session.pendingLines.removeFirst(session.pendingLines.count - 999)
        }
        session.pendingLines.append(line.hasSuffix("\n") ? line : line + "\n")
        session.condition.signal()
        session.condition.unlock()
    }

    /// 由 C 回调（Python 线程）调用：阻塞等待一行，30s 超时返回 nil（EOF）。
    func readLine(sessionId: String) -> String? {
        guard let session = session(id: sessionId) else { return nil }
        session.condition.lock()
        defer { session.condition.unlock() }
        let deadline = Date().addingTimeInterval(30)
        while session.pendingLines.isEmpty && !session.closed {
            if !session.condition.wait(until: deadline) && session.pendingLines.isEmpty {
                break
            }
        }
        guard !session.pendingLines.isEmpty else { return nil }
        return session.pendingLines.removeFirst()
    }

    // MARK: - 输出

    fileprivate func emit(sessionId: String, stream: String, text: String) {
        delegate?.pythonDidEmit(sessionId: sessionId, stream: stream, text: text)
    }
}

// MARK: - C 回调（全局函数指针，不能捕获上下文）

private let pythonEmitBridge: cp_emit_fn = { session, stream, text in
    guard let session, let stream, let text else { return }
    let sessionId = String(cString: session)
    let streamName = String(cString: stream)
    let payload = String(cString: text)
    PythonEngineIOS.shared.emit(sessionId: sessionId, stream: streamName, text: payload)
}

private let pythonReadBridge: cp_readline_fn = { session in
    guard let session else { return nil }
    let sessionId = String(cString: session)
    guard let line = PythonEngineIOS.shared.readLine(sessionId: sessionId) else {
        return nil
    }
    return strdup(line)
}
