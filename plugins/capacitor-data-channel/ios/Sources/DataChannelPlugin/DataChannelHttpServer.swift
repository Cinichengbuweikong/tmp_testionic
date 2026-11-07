import Foundation
import Network
import Capacitor

/// DataChannel **HTTP** Server（iOS，NWListener 手写 HTTP 实现）。
///
/// 只提供三个路由：
///   GET  /health            免 OTP
///   PUT/POST /upload?path=  需要 write OTP（流式写盘，append=1 追加）
///   GET  /download?path=    需要 read OTP（流式下载，支持 Range → 206）
///   OPTIONS *               免 OTP（CORS 预检）
///
/// 鉴权用 OTP（通过桥下发，抓包看不到生成过程）：
///   - Header: `Authorization: OTP <otp>`
///   - Query:  `?otp=<otp>`（媒体 `<video src>` 用）
///   - 校验失败统一 404
///
/// 两级连接限流：未认证 4 / 已认证 maxConnections（默认 16）。
final class DataChannelHttpServer {

    enum ServerError: Error {
        case listenFailed(String)
        case timeout
    }

    private enum Limits {
        static let startTimeout: TimeInterval = 5
    }

    private let rootURL: URL
    private let otpStore: OtpStore
    private let maxBodyBytes: Int64
    private let limiter: ConnectionLimiter
    private let queue = DispatchQueue(label: "capacitor.datachannel.http")
    private let stateLock = NSLock()
    private let handlersLock = NSLock()
    private var httpHandlers: [HTTPConnection] = []

    private var listener: NWListener?
    private var boundPort: UInt16 = 0
    private var running = false

    init(root: URL, otpStore: OtpStore, maxBodyBytes: Int64, maxConnections: Int) {
        self.rootURL = root
        self.otpStore = otpStore
        self.maxBodyBytes = maxBodyBytes
        self.limiter = ConnectionLimiter(maxActive: maxConnections)
    }

    // MARK: - 状态

    var isRunning: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return running
    }

    var port: UInt16 {
        stateLock.lock()
        defer { stateLock.unlock() }
        return boundPort
    }

    var activeConnections: Int {
        return limiter.activeCount
    }

    var pendingConnections: Int {
        return limiter.pendingCount
    }

    var maxConnections: Int {
        return limiter.maxActiveCount
    }

    // MARK: - 生命周期

    func start(port: UInt16) throws -> UInt16 {
        if isRunning, boundPort != 0 {
            return boundPort
        }
        if !FileManager.default.fileExists(atPath: rootURL.path) {
            try FileManager.default.createDirectory(at: rootURL, withIntermediateDirectories: true)
        }

        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        #if !targetEnvironment(simulator)
        parameters.requiredInterfaceType = .loopback
        #endif

        let newListener: NWListener
        do {
            if port == 0 {
                newListener = try NWListener(using: parameters)
            } else {
                guard let endpointPort = NWEndpoint.Port(rawValue: port) else {
                    throw ServerError.listenFailed("invalid port \(port)")
                }
                newListener = try NWListener(using: parameters, on: endpointPort)
            }
        } catch {
            throw ServerError.listenFailed("\(error)")
        }

        let semaphore = DispatchSemaphore(value: 0)
        var assignedPort: UInt16 = 0
        var failure: String?

        newListener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                assignedPort = newListener.port?.rawValue ?? 0
                semaphore.signal()
            case .failed(let error):
                failure = "\(error)"
                semaphore.signal()
            default:
                break
            }
        }
        newListener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        newListener.start(queue: queue)

        if semaphore.wait(timeout: .now() + Limits.startTimeout) == .timedOut {
            newListener.cancel()
            throw ServerError.timeout
        }
        if let failure = failure {
            newListener.cancel()
            throw ServerError.listenFailed(failure)
        }
        if assignedPort == 0 {
            assignedPort = newListener.port?.rawValue ?? 0
        }

        stateLock.lock()
        self.listener = newListener
        self.boundPort = assignedPort
        self.running = true
        stateLock.unlock()
        return assignedPort
    }

    func stop() {
        stateLock.lock()
        let current = listener
        listener = nil
        running = false
        boundPort = 0
        stateLock.unlock()
        current?.cancel()

        handlersLock.lock()
        let pending = httpHandlers
        handlersLock.unlock()
        for handler in pending {
            handler.shutdown()
        }
    }

    // MARK: - 连接

    private func accept(_ connection: NWConnection) {
        guard limiter.tryAcquirePending() else {
            CAPLog.print("[DataChannelHttp] reject: pending limit reached")
            connection.cancel()
            return
        }
        #if !targetEnvironment(simulator)
        guard case let .hostPort(host, _) = connection.endpoint else {
            CAPLog.print("[DataChannelHttp] reject: endpoint is not hostPort")
            connection.cancel()
            limiter.release(authenticated: false)
            return
        }
        let hostString = "\(host)".lowercased()
        if hostString != "127.0.0.1" && hostString != "::1" && hostString != "localhost" {
            CAPLog.print("[DataChannelHttp] reject: non-loopback host \(hostString)")
            connection.cancel()
            limiter.release(authenticated: false)
            return
        }
        #endif

        let handler = HTTPConnection(
            connection: connection,
            root: rootURL,
            otpStore: otpStore,
            maxBodyBytes: maxBodyBytes,
            limiter: limiter,
            server: self,
            queue: queue
        )
        registerHandler(handler)
        handler.start()
    }

    fileprivate func registerHandler(_ handler: HTTPConnection) {
        handlersLock.lock()
        httpHandlers.append(handler)
        handlersLock.unlock()
    }

    fileprivate func unregisterHandler(_ handler: HTTPConnection) {
        handlersLock.lock()
        httpHandlers.removeAll { $0 === handler }
        handlersLock.unlock()
    }
}

// MARK: - 单个 HTTP 连接

private final class HTTPConnection {

    private enum Phase {
        case headers
        case uploading
        case sending
        case done
    }

    private enum Limits {
        static let maxHeaderBytes = 32 * 1024
        static let maxHeaderLines = 64
        static let chunkSize = 64 * 1024
        static let receiveTimeout: TimeInterval = 20
    }

    private let connection: NWConnection
    private let root: URL
    private let otpStore: OtpStore
    private let maxBodyBytes: Int64
    private let limiter: ConnectionLimiter
    private weak var server: DataChannelHttpServer?
    private let queue: DispatchQueue

    private var phase: Phase = .headers
    private var headerData = Data()
    private var method = ""
    private var path = "/"
    private var query: [String: String] = [:]
    private var headers: [String: String] = [:]
    private var bodyLength: Int64 = 0
    private var bodyReceived: Int64 = 0
    private var uploadHandle: FileHandle?
    private var uploadPartURL: URL?
    private var uploadTargetURL: URL?
    private var finished = false
    private var authenticated = false

    init(
        connection: NWConnection,
        root: URL,
        otpStore: OtpStore,
        maxBodyBytes: Int64,
        limiter: ConnectionLimiter,
        server: DataChannelHttpServer,
        queue: DispatchQueue
    ) {
        self.connection = connection
        self.root = root
        self.otpStore = otpStore
        self.maxBodyBytes = maxBodyBytes
        self.limiter = limiter
        self.server = server
        self.queue = queue
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.receiveMore()
            case .failed(let error):
                CAPLog.print("[DataChannelHttp] connection failed: \(error)")
                self?.finish()
            case .cancelled:
                self?.finish()
            default:
                break
            }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + Limits.receiveTimeout) { [weak self] in
            guard let self = self, !self.finished else { return }
            self.sendAndClose(status: 408, reason: "Request Timeout", json: ["error": "timeout"], origin: nil)
        }
    }

    func shutdown() {
        finish()
    }

    // MARK: 接收

    private func receiveMore() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if error != nil {
                self.finish()
                return
            }
            if let data = data, !data.isEmpty {
                self.handleReceived(data)
            }
            if isComplete && !self.finished {
                self.finish()
                return
            }
            if self.phase == .headers || self.phase == .uploading {
                self.receiveMore()
            }
        }
    }

    private func handleReceived(_ data: Data) {
        switch phase {
        case .headers:
            headerData.append(data)
            guard let headerEnd = headerData.range(of: Data("\r\n\r\n".utf8)) else {
                if headerData.count > Limits.maxHeaderBytes {
                    sendAndClose(
                        status: 431,
                        reason: "Request Header Fields Too Large",
                        json: ["error": "headers too large"],
                        origin: nil
                    )
                }
                return
            }
            let headerPart = headerData[..<headerEnd.lowerBound]
            let bodyPrefix = Data(headerData[headerEnd.upperBound...])
            headerData = Data()
            guard parseHeaders(headerPart) else {
                sendAndClose(status: 400, reason: "Bad Request", json: ["error": "bad request"], origin: nil)
                return
            }
            beginRequest(bodyPrefix: bodyPrefix)
        case .uploading:
            writeBody(data)
        case .sending, .done:
            break
        }
    }

    // MARK: 请求解析

    private func parseHeaders(_ data: Data) -> Bool {
        guard let text = String(data: data, encoding: .isoLatin1) else { return false }
        let lines = text.components(separatedBy: "\r\n")
        guard lines.count <= Limits.maxHeaderLines else { return false }
        guard let requestLine = lines.first else { return false }
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else { return false }
        method = parts[0].uppercased()
        let target = String(parts[1])
        guard let url = URL(string: target.hasPrefix("/") ? target : "/" + target) else { return false }
        path = url.path.isEmpty ? "/" : url.path
        query = Self.parseQuery(url.query)

        var parsed: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let index = line.firstIndex(of: ":") else { continue }
            let key = String(line[..<index]).trimmingCharacters(in: .whitespaces).lowercased()
            let value = String(line[line.index(after: index)...]).trimmingCharacters(in: .whitespaces)
            parsed[key] = value
        }
        headers = parsed
        if let rawLength = headers["content-length"], let value = Int64(rawLength) {
            bodyLength = value
        } else {
            bodyLength = 0
        }
        return true
    }

    private func beginRequest(bodyPrefix: Data) {
        let origin = headers["origin"]
        if bodyLength > maxBodyBytes {
            sendAndClose(status: 413, reason: "Payload Too Large", json: ["error": "payload too large"], origin: origin)
            return
        }
        if method == "OPTIONS" {
            sendAndClose(status: 204, reason: "No Content", json: nil, origin: origin)
            return
        }
        if let origin = origin, !Self.isAllowedOrigin(origin) {
            finish()
            return
        }
        if method == "GET", path == "/health" {
            sendAndClose(status: 200, reason: "OK", json: ["ok": true, "name": "capacitor-data-channel"], origin: origin)
            return
        }

        // OTP 鉴权（一次性/可复用由 OTP 自身属性决定）
        let neededScope = (method == "PUT" || method == "POST") ? OtpStore.scopeWrite : OtpStore.scopeRead
        let otp = extractOtp()
        guard otpStore.consume(otp, scope: neededScope, path: query["path"] ?? "") else {
            sendAndClose(status: 404, reason: "Not Found", json: ["error": "not found"], origin: origin)
            return
        }
        guard limiter.promoteToActive() else {
            sendAndClose(status: 404, reason: "Not Found", json: ["error": "not found"], origin: origin)
            return
        }
        authenticated = true

        if (method == "PUT" || method == "POST"), path == "/upload", bodyLength > 0 {
            guard prepareUpload() else { return }
            phase = .uploading
            writeBody(bodyPrefix)
            return
        }

        if bodyLength > 0 {
            sendAndClose(status: 400, reason: "Bad Request", json: ["error": "body not allowed"], origin: origin)
            return
        }
        handleRequest()
    }

    private func extractOtp() -> String? {
        if let auth = headers["authorization"], auth.lowercased().hasPrefix("otp ") {
            return String(auth.dropFirst(4)).trimmingCharacters(in: .whitespaces)
        }
        return query["otp"]
    }

    private func prepareUpload() -> Bool {
        guard let target = try? resolveSafePath(query["path"]) else {
            sendAndClose(status: 400, reason: "Bad Request", json: ["error": "invalid path"], origin: headers["origin"])
            return false
        }
        let parent = target.deletingLastPathComponent()
        if !FileManager.default.fileExists(atPath: parent.path) {
            try? FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        }
        let append = Self.isAppend(query["append"])
        var handle: FileHandle?
        if append {
            if !FileManager.default.fileExists(atPath: target.path) {
                FileManager.default.createFile(atPath: target.path, contents: nil)
            }
            handle = try? FileHandle(forWritingTo: target)
            handle?.seekToEndOfFile()
            uploadPartURL = target
        } else {
            let part = URL(fileURLWithPath: target.path + ".part")
            FileManager.default.createFile(atPath: part.path, contents: nil)
            handle = try? FileHandle(forWritingTo: part)
            uploadPartURL = part
        }
        guard let writer = handle else {
            sendAndClose(status: 500, reason: "Internal Server Error", json: ["error": "cannot open file"], origin: headers["origin"])
            return false
        }
        uploadHandle = writer
        uploadTargetURL = target
        bodyReceived = 0
        return true
    }

    private func writeBody(_ data: Data) {
        guard let writer = uploadHandle else {
            finish()
            return
        }
        let remaining = bodyLength - bodyReceived
        if remaining <= 0 {
            completeUpload()
            return
        }
        let slice = data.prefix(Int(min(Int64(data.count), remaining)))
        if !slice.isEmpty {
            writer.write(Data(slice))
            bodyReceived += Int64(slice.count)
        }
        if bodyReceived >= bodyLength {
            completeUpload()
        }
    }

    private func completeUpload() {
        try? uploadHandle?.close()
        uploadHandle = nil

        let append = Self.isAppend(query["append"])
        if !append, let part = uploadPartURL, let target = uploadTargetURL {
            try? FileManager.default.removeItem(at: target)
            try? FileManager.default.moveItem(at: part, to: target)
        }
        var size: Int64 = 0
        if let target = uploadTargetURL, let attributes = try? FileManager.default.attributesOfItem(atPath: target.path) {
            size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        }
        let body: [String: Any] = [
            "path": query["path"] ?? "",
            "bytes": bodyReceived,
            "size": size,
            "appended": append
        ]
        sendJSON(status: 200, reason: "OK", body: body, origin: headers["origin"])
    }

    // MARK: 路由

    private func handleRequest() {
        let origin = headers["origin"]
        switch (method, path) {
        case ("GET", "/health"):
            sendAndClose(status: 200, reason: "OK", json: ["ok": true, "name": "capacitor-data-channel"], origin: origin)
        case ("GET", "/download"):
            handleDownload(origin: origin)
        case ("PUT", "/upload"), ("POST", "/upload"):
            handleEmptyUpload(origin: origin)
        default:
            sendAndClose(status: 404, reason: "Not Found", json: ["error": "not found"], origin: origin)
        }
    }

    private func handleEmptyUpload(origin: String?) {
        guard let target = try? resolveSafePath(query["path"]) else {
            sendAndClose(status: 400, reason: "Bad Request", json: ["error": "invalid path"], origin: origin)
            return
        }
        let parent = target.deletingLastPathComponent()
        if !FileManager.default.fileExists(atPath: parent.path) {
            try? FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        }
        FileManager.default.createFile(atPath: target.path, contents: nil)
        sendAndClose(
            status: 200,
            reason: "OK",
            json: ["path": query["path"] ?? "", "bytes": 0, "size": 0],
            origin: origin
        )
    }

    private func handleDownload(origin: String?) {
        guard let file = try? resolveSafePath(query["path"]) else {
            sendAndClose(status: 400, reason: "Bad Request", json: ["error": "invalid path"], origin: origin)
            return
        }
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: file.path, isDirectory: &isDirectory), !isDirectory.boolValue else {
            sendAndClose(status: 404, reason: "Not Found", json: ["error": "file not found"], origin: origin)
            return
        }
        let attributes = try? FileManager.default.attributesOfItem(atPath: file.path)
        let size = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
        guard size > 0 else {
            sendAndClose(status: 404, reason: "Not Found", json: ["error": "empty file"], origin: origin)
            return
        }

        var start: Int64 = 0
        var end: Int64 = size - 1
        var partial = false
        if let range = Self.parseRange(headers["range"], size: size) {
            start = range.0
            end = range.1
            partial = true
        }
        let length = end - start + 1

        var extra: [String: String] = [
            "Content-Type": Self.mimeType(for: file.lastPathComponent),
            "Content-Length": "\(length)",
            "Accept-Ranges": "bytes"
        ]
        if partial {
            extra["Content-Range"] = "bytes \(start)-\(end)/\(size)"
        }
        let head = Self.responseHeader(status: partial ? 206 : 200, reason: partial ? "Partial Content" : "OK", headers: extra, origin: origin)
        phase = .sending
        connection.send(content: head, completion: .contentProcessed { [weak self] error in
            guard let self = self else { return }
            if error != nil {
                self.finish()
                return
            }
            self.streamFile(file: file, offset: start, remaining: length)
        })
    }

    private func streamFile(file: URL, offset: Int64, remaining: Int64) {
        guard remaining > 0 else {
            finish()
            return
        }
        guard let handle = try? FileHandle(forReadingFrom: file) else {
            finish()
            return
        }
        handle.seek(toFileOffset: UInt64(offset))
        let toRead = Int(min(Int64(Limits.chunkSize), remaining))
        let chunk = handle.readData(ofLength: toRead)
        try? handle.close()
        if chunk.isEmpty {
            finish()
            return
        }
        connection.send(content: chunk, completion: .contentProcessed { [weak self] error in
            guard let self = self else { return }
            if error != nil {
                self.finish()
                return
            }
            self.streamFile(file: file, offset: offset + Int64(chunk.count), remaining: remaining - Int64(chunk.count))
        })
    }

    // MARK: 安全

    private func resolveSafePath(_ relPath: String?) throws -> URL {
        let raw = (relPath ?? "").trimmingCharacters(in: .whitespaces)
        if raw.isEmpty {
            throw PathError.invalid
        }
        if raw.contains("\0") {
            throw PathError.invalid
        }
        var rel = raw.replacingOccurrences(of: "\\", with: "/")
        while rel.hasPrefix("/") {
            rel.removeFirst()
        }
        if rel.hasPrefix("~") {
            throw PathError.invalid
        }
        for segment in rel.split(separator: "/", omittingEmptySubsequences: true) where segment == ".." {
            throw PathError.invalid
        }
        let candidate = root.appendingPathComponent(rel).standardizedFileURL.resolvingSymlinksInPath()
        let rootResolved = root.standardizedFileURL.resolvingSymlinksInPath()
        if candidate.path != rootResolved.path, !candidate.path.hasPrefix(rootResolved.path + "/") {
            throw PathError.invalid
        }
        return candidate
    }

    private enum PathError: Error {
        case invalid
    }

    // MARK: 响应

    private func sendJSON(status: Int, reason: String, body: Any, origin: String?) {
        guard let data = try? JSONSerialization.data(withJSONObject: body, options: []) else {
            finish()
            return
        }
        let extra: [String: String] = [
            "Content-Type": "application/json; charset=utf-8",
            "Content-Length": "\(data.count)"
        ]
        let head = Self.responseHeader(status: status, reason: reason, headers: extra, origin: origin)
        phase = .sending
        connection.send(content: head, completion: .contentProcessed { [weak self] error in
            guard let self = self else { return }
            if error != nil {
                self.finish()
                return
            }
            self.connection.send(content: data, completion: .contentProcessed { [weak self] _ in
                self?.finish()
            })
        })
    }

    private func sendAndClose(status: Int, reason: String, json: Any?, origin: String?) {
        guard !finished else { return }
        if let json = json {
            sendJSON(status: status, reason: reason, body: json, origin: origin)
            return
        }
        let head = Self.responseHeader(status: status, reason: reason, headers: [:], origin: origin)
        phase = .sending
        connection.send(content: head, completion: .contentProcessed { [weak self] _ in
            self?.finish()
        })
    }

    private static func responseHeader(status: Int, reason: String, headers: [String: String], origin: String?) -> Data {
        var lines = "HTTP/1.1 \(status) \(reason)\r\n"
        lines += "Connection: close\r\n"
        lines += "Cache-Control: no-store\r\n"
        lines += "X-Content-Type-Options: nosniff\r\n"
        lines += "Referrer-Policy: no-referrer\r\n"
        if let origin = origin, isAllowedOrigin(origin) {
            lines += "Access-Control-Allow-Origin: \(origin)\r\n"
            lines += "Access-Control-Allow-Methods: GET, PUT, POST, OPTIONS\r\n"
            lines += "Access-Control-Allow-Headers: Authorization, Content-Type, Range\r\n"
            lines += "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n"
        }
        for (key, value) in headers {
            lines += "\(key): \(value)\r\n"
        }
        lines += "\r\n"
        return Data(lines.utf8)
    }

    private func finish() {
        if finished {
            return
        }
        finished = true
        phase = .done
        try? uploadHandle?.close()
        uploadHandle = nil
        connection.cancel()
        server?.unregisterHandler(self)
        limiter.release(authenticated: authenticated)
    }

    // MARK: 工具

    private static func isAppend(_ value: String?) -> Bool {
        guard let value = value?.lowercased() else { return false }
        return value == "1" || value == "true"
    }

    private static func isAllowedOrigin(_ origin: String) -> Bool {
        let normalized = origin.trimmingCharacters(in: .whitespaces).lowercased()
        return ["capacitor://localhost", "https://localhost", "http://localhost", "ionic://localhost"].contains(normalized)
    }

    private static func parseQuery(_ raw: String?) -> [String: String] {
        var result: [String: String] = [:]
        guard let raw = raw, !raw.isEmpty else { return result }
        for pair in raw.split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let key = String(parts.first ?? "").removingPercentEncoding ?? ""
            let value = parts.count > 1 ? (String(parts[1]).removingPercentEncoding ?? "") : ""
            result[key] = value
        }
        return result
    }

    private static func parseRange(_ header: String?, size: Int64) -> (Int64, Int64)? {
        guard let header = header, size > 0 else { return nil }
        let value = header.trimmingCharacters(in: .whitespaces)
        guard value.lowercased().hasPrefix("bytes=") else { return nil }
        let spec = String(value.dropFirst(6)).trimmingCharacters(in: .whitespaces)
        if spec.contains(",") { return nil }
        let parts = spec.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        var start: Int64
        var end: Int64
        if parts[0].isEmpty {
            guard let suffix = Int64(parts[1]), suffix > 0 else { return nil }
            start = max(0, size - suffix)
            end = size - 1
        } else {
            guard let parsedStart = Int64(parts[0]) else { return nil }
            start = parsedStart
            end = parts[1].isEmpty ? size - 1 : (Int64(parts[1]) ?? size - 1)
        }
        guard start >= 0, start < size, end >= start else { return nil }
        return (start, min(end, size - 1))
    }

    private static func mimeType(for name: String) -> String {
        let lower = name.lowercased()
        if lower.hasSuffix(".mp4") || lower.hasSuffix(".m4v") { return "video/mp4" }
        if lower.hasSuffix(".webm") { return "video/webm" }
        if lower.hasSuffix(".mp3") { return "audio/mpeg" }
        if lower.hasSuffix(".m4a") { return "audio/mp4" }
        if lower.hasSuffix(".wav") { return "audio/wav" }
        if lower.hasSuffix(".png") { return "image/png" }
        if lower.hasSuffix(".jpg") || lower.hasSuffix(".jpeg") { return "image/jpeg" }
        if lower.hasSuffix(".webp") { return "image/webp" }
        if lower.hasSuffix(".gif") { return "image/gif" }
        if lower.hasSuffix(".json") { return "application/json; charset=utf-8" }
        if lower.hasSuffix(".txt") { return "text/plain; charset=utf-8" }
        if lower.hasSuffix(".pdf") { return "application/pdf" }
        return "application/octet-stream"
    }
}
