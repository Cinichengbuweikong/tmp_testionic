import Foundation
import Capacitor
import Security

/**
 * DataChannel 插件（iOS 侧，jsName = "DataChannel"）。
 *
 * ======================= 这个插件解决什么 =======================
 *
 * Capacitor 的 JS↔Native 桥是 JSON 序列化（复杂类型必须 base64），大体积二进制传输代价极高。
 * 本插件在原生侧启动一个**只监听回环接口的 HTTP + WebSocket server**：
 *  - HTTP：大文件上传/下载（流式、Range）；
 *  - WebSocket：与 WebView 实时双向二进制帧（`new WebSocket(wsUrl)`）。
 * 两者都完全绕开桥。
 *
 * ======================= 数据目录（rootDir） =======================
 *
 * 三种写法：
 *  1. **不传**（默认）：`Documents/dataChannel`
 *     （iOS 沙盒内 Documents，配合 Info.plist 的 UIFileSharingEnabled 可在"文件"App 中访问）；
 *  2. **相对路径**（如 `myData`）：Documents 下；
 *  3. **绝对路径**（如 `/var/mobile/.../Documents/xxx`）：**必须位于 App 沙盒内**
 *     （Documents / Application Support / Caches / tmp），否则拒绝——这是 iOS 的平台限制。
 *
 * ======================= 安全设计 =======================
 *
 * 1. 只监听回环（requiredInterfaceType = .loopback + 连接来源校验）；
 * 2. 随机端口 + 每次启动随机 32 字节 token（常量时间比较）；
 * 3. Origin 白名单；非法 Origin 静默断开；鉴权失败统一 404（隐藏服务存在）；
 * 4. 路径限制在 rootDir 内（standardized + 软链解析后校验前缀）；
 * 5. 请求体上限 + 请求头上限 + 读超时；上传 .part 原子写；日志不记录 token。
 */
@objc(DataChannelPlugin)
public class DataChannelPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "DataChannelPlugin"
    public let jsName = "DataChannel"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getInfo", returnType: CAPPluginReturnPromise)
    ]

    private var server: DataChannelServer?
    private var token = ""
    private var rootURL: URL?
    private var port: UInt16 = 0
    private var maxBodyBytes: Int64 = 512 * 1024 * 1024
    private var startedAt: Int64 = 0
    private var wsEcho = true

    deinit {
        server?.stop()
    }

    // ---------------------------------------------------------------- API

    @objc func start(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let server = self.server, server.isRunning {
                call.resolve(self.infoDict())
                return
            }

            let rawPort = call.getInt("port", 0)
            let requestedPort: UInt16 = (rawPort > 0 && rawPort <= 65535) ? UInt16(rawPort) : 0
            let rootDirOption = call.getString("rootDir")
            let configuredMax = call.getDouble("maxBodyBytes", 536_870_912)
            self.maxBodyBytes = Int64(max(1024, configuredMax))
            self.wsEcho = call.getBool("wsEcho", true)

            let root: URL
            do {
                root = try self.resolveRootDir(rootDirOption)
            } catch {
                call.reject("invalid rootDir: \(error.localizedDescription)")
                return
            }

            let fileManager = FileManager.default
            do {
                if !fileManager.fileExists(atPath: root.path) {
                    try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
                }
            } catch {
                call.reject("cannot create rootDir: \(error.localizedDescription)")
                return
            }
            if !fileManager.isWritableFile(atPath: root.path) {
                call.reject("rootDir is not writable: \(root.path)")
                return
            }

            self.rootURL = root
            self.token = Self.generateToken()
            let newServer = DataChannelServer(
                root: root,
                token: self.token,
                maxBodyBytes: self.maxBodyBytes,
                wsEcho: self.wsEcho
            )
            do {
                let bound = try newServer.start(port: requestedPort)
                self.server = newServer
                self.port = bound
                self.startedAt = Int64(Date().timeIntervalSince1970 * 1000)
                CAPLog.print("[DataChannel] started on 127.0.0.1:\(bound) root=\(root.path) wsEcho=\(self.wsEcho)")
                // 原生侧自检：用 URLSession 直接请求 /health，用于区分"server 本身有问题"和"WebView 侧限制"
                self.runNativeSelfTest(port: bound)
                let info = self.infoDict()
                call.resolve(info)
                self.notifyListeners("channelStateChanged", data: info)
            } catch {
                call.reject("start failed: \(error.localizedDescription)")
            }
        }
    }

    @objc func stop(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let wasRunning = self.server?.isRunning ?? false
            self.server?.stop()
            self.server = nil
            self.token = ""
            self.port = 0
            self.startedAt = 0
            let info = self.infoDict()
            call.resolve(info)
            if wasRunning {
                self.notifyListeners("channelStateChanged", data: info)
            }
        }
    }

    @objc func getInfo(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            call.resolve(self.infoDict())
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 解析 rootDir：
     *  - nil/空 → Documents/dataChannel（"文件"App 可见）
     *  - 相对路径 → Documents 下
     *  - 绝对路径 / file:// → 必须位于沙盒内（Documents / Application Support / Caches / tmp）
     */
    private func resolveRootDir(_ requested: String?) throws -> URL {
        let fileManager = FileManager.default
        guard let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            throw NSError(
                domain: "DataChannel",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "cannot resolve documents directory"]
            )
        }

        let trimmed = (requested ?? "").trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty {
            return documents.appendingPathComponent("dataChannel", isDirectory: true)
        }

        if trimmed.hasPrefix("/") || trimmed.hasPrefix("file://") {
            var path = trimmed
            if trimmed.hasPrefix("file://") {
                path = String(trimmed.dropFirst("file://".count))
            }
            let target = URL(fileURLWithPath: path).standardizedFileURL.resolvingSymlinksInPath()
            let sandboxRoots: [URL] = [
                documents,
                fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? documents,
                fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first ?? documents,
                URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            ]
            let allowed = sandboxRoots.contains { root in
                let base = root.standardizedFileURL.resolvingSymlinksInPath().path
                return target.path == base || target.path.hasPrefix(base + "/")
            }
            if !allowed {
                throw NSError(
                    domain: "DataChannel",
                    code: 2,
                    userInfo: [
                        NSLocalizedDescriptionKey:
                            "iOS only allows paths inside the app sandbox (Documents / Application Support / Caches / tmp)"
                    ]
                )
            }
            return target
        }

        let candidate = documents.appendingPathComponent(trimmed, isDirectory: true).standardizedFileURL
        let base = documents.standardizedFileURL.path
        if candidate.path != base, !candidate.path.hasPrefix(base + "/") {
            throw NSError(
                domain: "DataChannel",
                code: 3,
                userInfo: [NSLocalizedDescriptionKey: "rootDir escapes documents directory"]
            )
        }
        return candidate
    }

    /// 原生侧自检：用 URLSession 请求 /health。
    /// 用于区分"HTTP server 实现有问题"与"WebView 侧被 ATS/混合内容策略阻止"。
    private func runNativeSelfTest(port: UInt16) {
        guard let url = URL(string: "http://127.0.0.1:\(port)/health") else {
            return
        }
        let task = URLSession.shared.dataTask(with: url) { data, response, error in
            if let error = error {
                CAPLog.print("[DataChannel] native self-test FAILED: \(error.localizedDescription)")
                return
            }
            guard let http = response as? HTTPURLResponse else {
                CAPLog.print("[DataChannel] native self-test FAILED: no HTTP response")
                return
            }
            let body = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            CAPLog.print("[DataChannel] native self-test OK: HTTP \(http.statusCode) body=\(body)")
        }
        task.resume()
    }

    private func infoDict() -> [String: Any] {        let running = self.server?.isRunning ?? false
        return [
            "running": running,
            "port": running ? Int(self.port) : 0,
            "baseUrl": running ? "http://127.0.0.1:\(self.port)" : "",
            "wsUrl": running ? "ws://127.0.0.1:\(self.port)/ws?token=\(self.token)" : "",
            "token": running ? self.token : "",
            "rootPath": self.rootURL?.path ?? "",
            "maxBodyBytes": self.maxBodyBytes,
            "startedAt": running ? self.startedAt : 0,
            "activeConnections": running ? (self.server?.activeConnections ?? 0) : 0,
            "wsClients": running ? (self.server?.wsClients ?? 0) : 0,
            "webSocketEnabled": running
        ]
    }

    private static func generateToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard status == errSecSuccess else {
            return UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}
