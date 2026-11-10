import Foundation
import Capacitor

/**
 * DataChannel 插件（iOS 侧，jsName = "DataChannel"）。
 *
 * 两个**完全独立**的本地 server，可分别启停：
 *  - **HTTP**（startHttp/stopHttp）：大文件上传/下载（流式、Range）；
 *  - **WebSocket**（startWebSocket/stopWebSocket）：实时双向二进制帧。
 *
 * 鉴权用 **OTP**（通过 Capacitor 桥下发，抓包看不到生成过程）：
 *  - 一次性（默认）：用一次作废，无时间限制；
 *  - 可复用（仅 read，媒体用）：**必须用完 revoke**，否则 server 运行期间永久有效；
 *  - 写操作不允许 reusable；
 *  - 无 TTL：等价于 server 生命周期。
 *
 * 连接限流：未认证 4 / 已认证 maxConnections（默认 16）。
 *
 * 职责边界：本插件只负责传输；存储权限/文件管理由 `capacitor-native-files` 提供。
 */
@objc(DataChannelPlugin)
public class DataChannelPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "DataChannelPlugin"
    public let jsName = "DataChannel"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "startHttp", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopHttp", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startWebSocket", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopWebSocket", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getHttpInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getWsInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createHttpOtp", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createWsOtp", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createWsOtps", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createHttpOtps", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "validateAndConsumeOTP", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "validateAndConsumeOtps", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "revokeOtp", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "revokeAllOtps", returnType: CAPPluginReturnPromise)
    ]

    private static let defaultMaxBodyBytes: Int64 = 512 * 1024 * 1024
    private static let defaultMaxConnections = 16

    // HTTP
    private var httpServer: DataChannelHttpServer?
    private var httpOtps: OtpStore?
    private var httpRoot: URL?
    private var httpPort: UInt16 = 0
    private var httpStartedAt: Int64 = 0
    private var maxBodyBytes: Int64 = DataChannelPlugin.defaultMaxBodyBytes
    private var httpMaxConnections = DataChannelPlugin.defaultMaxConnections

    // WebSocket
    private var wsServer: DataChannelWsServer?
    private var wsOtps: OtpStore?
    private var wsPort: UInt16 = 0
    private var wsStartedAt: Int64 = 0
    private var wsEcho = true
    private var wsMaxConnections = DataChannelPlugin.defaultMaxConnections

    deinit {
        httpServer?.stop()
        wsServer?.stop()
    }

    // ---------------------------------------------------------------- HTTP

    @objc func startHttp(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let server = self.httpServer, server.isRunning {
                call.resolve(self.httpInfo())
                return
            }
            let rawPort = call.getInt("port", 0)
            let requestedPort: UInt16 = (rawPort > 0 && rawPort <= 65535) ? UInt16(rawPort) : 0
            let rootDirOption = call.getString("rootDir")
            let configuredMax = call.getDouble("maxBodyBytes", Double(Self.defaultMaxBodyBytes))
            self.maxBodyBytes = Int64(max(1024, configuredMax))
            let configuredConnections = call.getInt("maxConnections", Self.defaultMaxConnections)
            self.httpMaxConnections = max(1, configuredConnections)

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

            self.applyDebugOrigins(call)

            let otps = OtpStore()
            let server = DataChannelHttpServer(
                root: root,
                otpStore: otps,
                maxBodyBytes: self.maxBodyBytes,
                maxConnections: self.httpMaxConnections
            )
            do {
                let bound = try server.start(port: requestedPort)
                self.httpOtps = otps
                self.httpRoot = root
                self.httpServer = server
                self.httpPort = bound
                self.httpStartedAt = Int64(Date().timeIntervalSince1970 * 1000)
                CAPLog.print("[DataChannel] HTTP started on 127.0.0.1:\(bound) root=\(root.path)")
                call.resolve(self.httpInfo())
                self.notifyStateChanged()
            } catch {
                call.reject("startHttp failed: \(error.localizedDescription)")
            }
        }
    }

    @objc func stopHttp(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.stopHttpInternal()
            call.resolve(self.httpInfo())
            self.notifyStateChanged()
        }
    }

    // ---------------------------------------------------------------- WebSocket

    @objc func startWebSocket(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let server = self.wsServer, server.isRunning {
                call.resolve(self.wsInfo())
                return
            }
            let rawPort = call.getInt("port", 0)
            let requestedPort: UInt16 = (rawPort > 0 && rawPort <= 65535) ? UInt16(rawPort) : 0
            self.wsEcho = call.getBool("wsEcho", true)
            let configuredConnections = call.getInt("maxConnections", Self.defaultMaxConnections)
            self.wsMaxConnections = max(1, configuredConnections)

            self.applyDebugOrigins(call)

            let otps = OtpStore()
            let server = DataChannelWsServer(otpStore: otps, wsEcho: self.wsEcho, maxConnections: self.wsMaxConnections)
            do {
                let bound = try server.start(port: requestedPort)
                self.wsOtps = otps
                self.wsServer = server
                self.wsPort = bound
                self.wsStartedAt = Int64(Date().timeIntervalSince1970 * 1000)
                CAPLog.print("[DataChannel] WebSocket started on 127.0.0.1:\(bound) (echo=\(self.wsEcho))")
                call.resolve(self.wsInfo())
                self.notifyStateChanged()
            } catch {
                call.reject("startWebSocket failed: \(error.localizedDescription)")
            }
        }
    }

    @objc func stopWebSocket(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.stopWebSocketInternal()
            call.resolve(self.wsInfo())
            self.notifyStateChanged()
        }
    }

    // ---------------------------------------------------------------- 状态

    @objc func getHttpInfo(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            call.resolve(self.httpInfo())
        }
    }

    @objc func getWsInfo(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            call.resolve(self.wsInfo())
        }
    }

    // ---------------------------------------------------------------- OTP

    @objc func createHttpOtp(_ call: CAPPluginCall) {
        guard let otps = httpOtps, let server = httpServer, server.isRunning else {
            call.reject("HTTP server is not running; call startHttp() first")
            return
        }
        let scope = call.getString("scope", OtpStore.scopeRead) ?? OtpStore.scopeRead
        guard let path = call.getString("path"), !path.trimmingCharacters(in: .whitespaces).isEmpty else {
            call.reject("missing path: an HTTP OTP must be bound to a specific file/folder")
            return
        }
        let reusable = call.getBool("reusable", false)
        do {
            let otp = try otps.create(scope: scope, path: path, reusable: reusable)
            let normalizedPath = (try? OtpStore.normalizePath(path)) ?? path
            let effectiveScope = scope.trimmingCharacters(in: .whitespaces).isEmpty ? OtpStore.scopeRead : scope
            call.resolve([
                "otp": otp,
                "scope": effectiveScope,
                "path": normalizedPath,
                "reusable": reusable
            ])
        } catch {
            call.reject(error.localizedDescription)
        }
    }

    @objc func createWsOtp(_ call: CAPPluginCall) {
        guard let otps = wsOtps, let server = wsServer, server.isRunning else {
            call.reject("WebSocket server is not running; call startWebSocket() first")
            return
        }
        do {
            let otp = try otps.create(scope: OtpStore.scopeReadWrite, path: "", reusable: false)
            call.resolve(["otp": otp, "reusable": false])
        } catch {
            call.reject(error.localizedDescription)
        }
    }

    /// 验证并消费一个 OTP（WebView 校验 Server → Client 的 WS 消息用）。
    @objc func validateAndConsumeOTP(_ call: CAPPluginCall) {
        guard let otp = call.getString("otp"), !otp.isEmpty else {
            call.reject("missing otp")
            return
        }
        var valid = false
        if let otps = httpOtps {
            valid = otps.revoke(otp) || valid
        }
        if let otps = wsOtps {
            valid = otps.revoke(otp) || valid
        }
        call.resolve(["valid": valid])
    }

    /// 批量创建 WebSocket OTP（一次桥调用生成 N 个；高频小包场景用）。
    @objc func createWsOtps(_ call: CAPPluginCall) {
        guard let otps = wsOtps, let server = wsServer, server.isRunning else {
            call.reject("WebSocket server is not running; call startWebSocket() first")
            return
        }
        let count = max(1, min(256, call.getInt("count", 1)))
        do {
            let created = try otps.createBatch(count: count, scope: OtpStore.scopeReadWrite, path: "", reusable: false)
            call.resolve(["otps": created, "count": created.count])
        } catch {
            call.reject(error.localizedDescription)
        }
    }

    /// 批量创建 HTTP OTP（一次桥调用生成 N 个）。
    @objc func createHttpOtps(_ call: CAPPluginCall) {
        guard let otps = httpOtps, let server = httpServer, server.isRunning else {
            call.reject("HTTP server is not running; call startHttp() first")
            return
        }
        guard let path = call.getString("path"), !path.trimmingCharacters(in: .whitespaces).isEmpty else {
            call.reject("missing path: an HTTP OTP must be bound to a specific file/folder")
            return
        }
        let scope = call.getString("scope", OtpStore.scopeRead) ?? OtpStore.scopeRead
        let reusable = call.getBool("reusable", false)
        let count = max(1, min(256, call.getInt("count", 1)))
        do {
            let created = try otps.createBatch(count: count, scope: scope, path: path, reusable: reusable)
            let normalizedPath = (try? OtpStore.normalizePath(path)) ?? path
            call.resolve([
                "otps": created,
                "count": created.count,
                "scope": scope,
                "path": normalizedPath,
                "reusable": reusable
            ])
        } catch {
            call.reject(error.localizedDescription)
        }
    }

    /// 批量验证并消费 OTP（返回结果已全部验证；未通过项必须丢弃）。
    @objc func validateAndConsumeOtps(_ call: CAPPluginCall) {
        let raw = call.getArray("otps") ?? []
        let input: [String] = raw.compactMap { $0 as? String }
        if input.isEmpty {
            call.reject("missing otps")
            return
        }
        var results: [Bool] = []
        results.reserveCapacity(input.count)
        for otp in input {
            var valid = false
            if !otp.isEmpty {
                if let otps = httpOtps {
                    valid = otps.revoke(otp) || valid
                }
                if let otps = wsOtps {
                    valid = otps.revoke(otp) || valid
                }
            }
            results.append(valid)
        }
        call.resolve(["results": results])
    }

    @objc func revokeOtp(_ call: CAPPluginCall) {
        guard let otp = call.getString("otp"), !otp.isEmpty else {
            call.reject("missing otp")
            return
        }
        var revoked = false
        if let otps = httpOtps {
            revoked = otps.revoke(otp) || revoked
        }
        if let otps = wsOtps {
            revoked = otps.revoke(otp) || revoked
        }
        call.resolve(["revoked": revoked])
    }

    @objc func revokeAllOtps(_ call: CAPPluginCall) {
        let server = call.getString("server")
        var revoked = 0
        if let otps = httpOtps, server == nil || server?.lowercased() == "http" {
            revoked += otps.revokeAll()
        }
        if let otps = wsOtps,
           server == nil || server?.lowercased() == "ws" || server?.lowercased() == "websocket" {
            revoked += otps.revokeAll()
        }
        call.resolve(["revoked": revoked])
    }

    // ---------------------------------------------------------------- 内部

    private func stopHttpInternal() {
        httpServer?.stop()
        httpServer = nil
        httpOtps?.revokeAll()
        httpOtps = nil
        httpPort = 0
        httpStartedAt = 0
    }

    private func stopWebSocketInternal() {
        wsServer?.stop()
        wsServer = nil
        wsOtps?.revokeAll()
        wsOtps = nil
        wsPort = 0
        wsStartedAt = 0
    }

    private func notifyStateChanged() {
        notifyListeners("stateChanged", data: channelInfo())
    }

    private func channelInfo() -> [String: Any] {
        var info: [String: Any] = [:]
        // 用 NSNull 显式写 null（Swift 字典赋 nil 会移除键，而不是 JSON null）
        info["http"] = (httpServer?.isRunning ?? false) ? httpInfo() : NSNull()
        info["webSocket"] = (wsServer?.isRunning ?? false) ? wsInfo() : NSNull()
        return info
    }

    /// 开发环境（Live Reload）下放行当前页面 origin；`debug: false` 时清空额外白名单。
    ///
    /// Live Reload 时 WebView 加载的是 `http://<host>:<port>`，其 origin（带端口）不在默认白名单里，
    /// 不额外放行会导致 fetch 挂起 / WebSocket 被静默断开。
    private func applyDebugOrigins(_ call: CAPPluginCall) {
        let debug = call.getBool("debug", false)
        guard debug else {
            DataChannelOrigins.setExtra([])
            return
        }
        guard let origin = DataChannelOrigins.origin(of: self.bridge?.webView?.url) else {
            CAPLog.print("[DataChannel] debug=true but cannot derive page origin, extra origins stays empty")
            DataChannelOrigins.setExtra([])
            return
        }
        CAPLog.print("[DataChannel] debug mode: allowing dev origin \(origin)")
        DataChannelOrigins.setExtra([origin])
    }
    private func httpInfo() -> [String: Any] {
        let running = httpServer?.isRunning ?? false
        return [
            "running": running,
            "port": running ? Int(httpPort) : 0,
            "baseUrl": running ? "http://127.0.0.1:\(httpPort)" : "",
            "rootPath": httpRoot?.path ?? "",
            "maxBodyBytes": maxBodyBytes,
            "maxConnections": httpMaxConnections,
            "activeConnections": running ? (httpServer?.activeConnections ?? 0) : 0,
            "pendingConnections": running ? (httpServer?.pendingConnections ?? 0) : 0,
            "otpCount": httpOtps?.count ?? 0,
            "startedAt": running ? httpStartedAt : 0
        ]
    }

    private func wsInfo() -> [String: Any] {
        let running = wsServer?.isRunning ?? false
        return [
            "running": running,
            "port": running ? Int(wsPort) : 0,
            "wsUrl": running ? "ws://127.0.0.1:\(wsPort)/ws" : "",
            "wsEcho": wsEcho,
            "maxConnections": wsMaxConnections,
            "activeConnections": running ? (wsServer?.activeConnections ?? 0) : 0,
            "pendingConnections": running ? (wsServer?.pendingConnections ?? 0) : 0,
            "clients": running ? (wsServer?.clientCount ?? 0) : 0,
            "otpCount": wsOtps?.count ?? 0,
            "startedAt": running ? wsStartedAt : 0
        ]
    }

    /**
     * 解析 rootDir：
     *  - nil/空 → Documents/dataChannel（App 私有沙盒）；
     *  - 相对路径 → Documents 下；
     *  - 绝对路径 / file:// → 必须位于沙盒内（Documents / Application Support / Caches / tmp）。
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
}
