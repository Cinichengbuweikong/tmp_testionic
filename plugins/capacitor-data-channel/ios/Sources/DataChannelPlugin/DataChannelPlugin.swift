import Foundation
import Capacitor
import Security

/**
 * DataChannel 插件（iOS 侧，jsName = "DataChannel"）。
 *
 * ======================= 这个插件解决什么 =======================
 *
 * Capacitor 的 JS↔Native 桥是 JSON 序列化（复杂类型必须 base64），大体积二进制传输代价极高。
 * 本插件在原生侧启动一个**只监听回环接口的 HTTP server**，让 WebView 用标准 fetch /
 * &lt;video&gt; / &lt;img&gt; 直接收发原始二进制，彻底绕开桥。
 *
 * ======================= 安全设计 =======================
 *
 * 1. NWParameters.requiredInterfaceType = .loopback + 连接来源校验：只服务本机回环；
 * 2. 随机端口 + 每次启动随机 32 字节 token（SecRandomCopyBytes 生成，常量时间比较）；
 *    token 可通过 `Authorization: Bearer <t>` 或 `?token=` 传递（后者给 &lt;video src&gt; 用）；
 * 3. Origin 白名单（capacitor://localhost / https://localhost 等）；
 * 4. 数据根目录固定在 App 私有目录（Application Support/data-channel）内，
 *    canonical path 校验拒绝 .. / 绝对路径 / 软链逃逸；
 * 5. 请求体大小上限 + 请求头上限 + 读超时；上传先写 .part 再 rename；日志不记录 token。
 *
 * 数据根目录：Application Support/<rootDir>（默认 data-channel），rootPath 会返回绝对路径，
 * 便于后续「本地文件系统」插件直接复用该目录。
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
            let rootDir = call.getString("rootDir", "data-channel") ?? "data-channel"
            let configuredMax = call.getDouble("maxBodyBytes", 536_870_912)
            self.maxBodyBytes = Int64(max(1024, configuredMax))

            let fileManager = FileManager.default
            guard let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
                call.reject("cannot resolve application support directory")
                return
            }
            let trimmedDir = rootDir.trimmingCharacters(in: .whitespaces)
            guard !trimmedDir.isEmpty, !trimmedDir.contains(".."), !trimmedDir.hasPrefix("/") else {
                call.reject("invalid rootDir")
                return
            }
            let root = base.appendingPathComponent(trimmedDir, isDirectory: true).standardizedFileURL
            let basePath = base.standardizedFileURL.path
            if root.path != basePath, !root.path.hasPrefix(basePath + "/") {
                call.reject("invalid rootDir: escapes app support directory")
                return
            }
            do {
                if !fileManager.fileExists(atPath: root.path) {
                    try fileManager.createDirectory(at: root, withIntermediateDirectories: true)
                }
            } catch {
                call.reject("cannot create data root: \(error)")
                return
            }

            self.rootURL = root
            self.token = Self.generateToken()
            let newServer = DataChannelServer(root: root, token: self.token, maxBodyBytes: self.maxBodyBytes)
            do {
                let bound = try newServer.start(port: requestedPort)
                self.server = newServer
                self.port = bound
                self.startedAt = Int64(Date().timeIntervalSince1970 * 1000)
                CAPLog.print("[DataChannel] started on 127.0.0.1:\(bound) root=\(root.path)")
                let info = self.infoDict()
                call.resolve(info)
                self.notifyListeners("channelStateChanged", data: info)
            } catch {
                call.reject("start failed: \(error)")
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

    private func infoDict() -> [String: Any] {
        let running = self.server?.isRunning ?? false
        return [
            "running": running,
            "port": running ? Int(self.port) : 0,
            "baseUrl": running ? "http://127.0.0.1:\(self.port)" : "",
            "token": running ? self.token : "",
            "rootPath": self.rootURL?.path ?? "",
            "maxBodyBytes": self.maxBodyBytes,
            "startedAt": running ? self.startedAt : 0,
            "activeConnections": running ? (self.server?.activeConnections ?? 0) : 0
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
