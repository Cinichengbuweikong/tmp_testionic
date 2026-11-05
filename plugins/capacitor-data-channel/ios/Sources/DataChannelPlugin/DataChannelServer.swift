import Foundation
import Network
import Security
import CryptoKit

/// DataChannel 本地 HTTP + WebSocket Server（iOS，纯 Foundation/Network 实现，零第三方依赖）。
///
/// 与 Android 侧 `DataChannelServer.java` 协议完全一致：
///   HTTP: GET /health、PUT|POST /upload、GET /download（支持 Range）、GET /info、GET /list、DELETE /file
///   WS:   GET /ws（Upgrade，RFC 6455 子集，默认 echo）
///
/// 安全设计：
///  - `NWParameters.requiredInterfaceType = .loopback` + 连接来源校验：只服务本机回环；
///  - 随机端口 + 每次启动随机 32 字节 Bearer token（常量时间比较）；
///  - Origin 白名单（capacitor://localhost / https://localhost 等）；
///  - 路径限制在数据根目录内（standardized + 解析软链后校验前缀）；
///  - 请求头/请求体上限 + 读超时；上传先写 .part 再 rename。
final class DataChannelServer {

    enum ServerError: Error {
        case listenFailed(String)
        case timeout
    }

    private enum Limits {
        static let maxHeaderBytes = 32 * 1024
        static let maxHeaderLines = 64
        static let chunkSize = 64 * 1024
        static let receiveTimeout: TimeInterval = 30
        static let startTimeout: TimeInterval = 5
        static let wsMaxFrameBytes = 64 * 1024 * 1024
    }

    private let rootURL: URL
    private let token: String
    private let maxBodyBytes: Int64
    private let wsEcho: Bool
    /// NWListener / NWConnection 需要串行队列
    private let queue = DispatchQueue(label: "capacitor.datachannel")
    private let stateLock = NSLock()
    private let sessionsLock = NSLock()
    private var wsSessions: [HTTPConnection] = []

    private var listener: NWListener?
    private var running = false
    private var activeConnectionCount = 0

    init(root: URL, token: String, maxBodyBytes: Int64, wsEcho: Bool) {
        self.rootURL = root
        self.token = token
        self.maxBodyBytes = maxBodyBytes
        self.wsEcho = wsEcho
    }

    var isRunning: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return running
    }

    var activeConnections: Int {
        stateLock.lock()
        defer { stateLock.unlock() }
        return activeConnectionCount
    }

    var wsClients: Int {
        sessionsLock.lock()
        defer { sessionsLock.unlock() }
        return wsSessions.count
    }

    /// 向所有已连接的 WebSocket 客户端广播二进制帧（供 native 侧扩展使用）。
    @discardableResult
    func broadcast(_ payload: Data) -> Int {
        sessionsLock.lock()
        let sessions = wsSessions
        sessionsLock.unlock()
        for session in sessions {
            session.sendBinaryFrame(payload)
        }
        return sessions.count
    }

    fileprivate func registerWebSocketSession(_ session: HTTPConnection) {
        sessionsLock.lock()
        wsSessions.append(session)
        sessionsLock.unlock()
    }

    fileprivate func unregisterWebSocketSession(_ session: HTTPConnection) {
        sessionsLock.lock()
        wsSessions.removeAll { $0 === session }
        sessionsLock.unlock()
    }

    /// 启动并返回实际端口（port=0 时由系统分配）。
    func start(port: UInt16) throws -> UInt16 {
        if isRunning, let existing = listener, let bound = existing.port?.rawValue {
            return bound
        }
        if !FileManager.default.fileExists(atPath: rootURL.path) {
            try FileManager.default.createDirectory(at: rootURL, withIntermediateDirectories: true)
        }

        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        // 真机：强制只使用回环接口（避免监听 0.0.0.0 触发 iOS 本地网络权限）。
        // 模拟器：requiredInterfaceType = .loopback 会导致 listener 接受不到连接
        // （已知兼容性问题），而模拟器与宿主共享网络栈、外部无法连入，因此跳过。
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
        var boundPort: UInt16 = 0
        var failure: String?

        newListener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                boundPort = newListener.port?.rawValue ?? 0
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
        if boundPort == 0 {
            boundPort = newListener.port?.rawValue ?? 0
        }

        stateLock.lock()
        self.listener = newListener
        self.running = true
        stateLock.unlock()
        return boundPort
    }

    func stop() {
        stateLock.lock()
        let current = listener
        listener = nil
        running = false
        stateLock.unlock()
        current?.cancel()
    }

    // MARK: - 连接

    private func accept(_ connection: NWConnection) {
        CAPLog.print("[DataChannel] accept connection from \(connection.endpoint)")
        // 模拟器上 endpoint 可能不可用，跳过来源校验（模拟器网络是 NAT 的，外部连不进来）
        #if !targetEnvironment(simulator)
        guard case let .hostPort(host, _) = connection.endpoint else {
            CAPLog.print("[DataChannel] reject: endpoint is not hostPort")
            connection.cancel()
            return
        }
        let hostString = "\(host)".lowercased()
        if hostString != "127.0.0.1" && hostString != "::1" && hostString != "localhost" {
            CAPLog.print("[DataChannel] reject: non-loopback host \(hostString)")
            connection.cancel()
            return
        }
        #endif

        stateLock.lock()
        activeConnectionCount += 1
        stateLock.unlock()

        let handler = HTTPConnection(
            connection: connection,
            root: rootURL,
            token: token,
            maxBodyBytes: maxBodyBytes,
            wsEcho: wsEcho,
            server: self,
            queue: queue,
            onFinish: { [weak self] in
                guard let self = self else { return }
                self.stateLock.lock()
                self.activeConnectionCount = max(0, self.activeConnectionCount - 1)
                self.stateLock.unlock()
            }
        )
        handler.start()
    }
}

// MARK: - 单个连接（HTTP 解析 + 响应）

private final class HTTPConnection {

    private enum Phase {
        case headers
        case uploading
        case sending
        case webSocket
        case done
    }

    private enum Limits {
        static let maxHeaderBytes = 32 * 1024
        static let maxHeaderLines = 64
        static let chunkSize = 64 * 1024
        static let receiveTimeout: TimeInterval = 30
        static let wsMaxFrameBytes = 64 * 1024 * 1024
    }

    private let connection: NWConnection
    private let root: URL
    private let token: String
    private let maxBodyBytes: Int64
    private let wsEcho: Bool
    private weak var server: DataChannelServer?
    private let queue: DispatchQueue
    private let onFinish: () -> Void

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

    // WebSocket 状态
    private var wsBuffer: [UInt8] = []
    private var wsAssembled = Data()
    private var wsAssembledOpcode: UInt8 = 0
    private var webSocketOpen = false

    init(
        connection: NWConnection,
        root: URL,
        token: String,
        maxBodyBytes: Int64,
        wsEcho: Bool,
        server: DataChannelServer,
        queue: DispatchQueue,
        onFinish: @escaping () -> Void
    ) {
        self.connection = connection
        self.root = root
        self.token = token
        self.maxBodyBytes = maxBodyBytes
        self.wsEcho = wsEcho
        self.server = server
        self.queue = queue
        self.onFinish = onFinish
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                CAPLog.print("[DataChannel] connection ready")
                self?.receiveMore()
            case .failed(let error):
                CAPLog.print("[DataChannel] connection failed: \(error)")
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
            self.sendAndClose(
                status: 408,
                reason: "Request Timeout",
                json: ["error": "timeout"],
                origin: nil
            )
        }
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
                CAPLog.print("[DataChannel] bad request headers")
                sendAndClose(status: 400, reason: "Bad Request", json: ["error": "bad request"], origin: nil)
                return
            }
            CAPLog.print("[DataChannel] request \(method) \(path) origin=\(headers["origin"] ?? "-")")
            beginRequest(bodyPrefix: bodyPrefix)
        case .uploading:
            writeBody(data)
        case .sending, .webSocket, .done:
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
            // 静默断开：不返回任何响应，避免泄露服务存在
            finish()
            return
        }
        // 健康检查（免 token，与 Android 侧行为一致）
        if method == "GET", path == "/health" {
            sendAndClose(status: 200, reason: "OK", json: ["ok": true, "name": "capacitor-data-channel"], origin: origin)
            return
        }

        if !isAuthorized() {
            // 鉴权失败统一 404（与未知路由不可区分），不泄露"需要认证的服务"
            sendAndClose(status: 404, reason: "Not Found", json: ["error": "not found"], origin: origin)
            return
        }

        // WebSocket 升级（同端口 /ws）
        if path == "/ws", (headers["upgrade"] ?? "").lowercased() == "websocket" {
            guard let key = headers["sec-websocket-key"], !key.isEmpty else {
                sendAndClose(status: 400, reason: "Bad Request", json: ["error": "missing Sec-WebSocket-Key"], origin: origin)
                return
            }
            beginWebSocket(key: key, origin: origin)
            return
        }

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

    // MARK: WebSocket

    private func beginWebSocket(key: String, origin: String?) {
        guard let accept = Self.wsAccept(key) else {
            finish()
            return
        }
        var response = "HTTP/1.1 101 Switching Protocols\r\n"
        response += "Upgrade: websocket\r\n"
        response += "Connection: Upgrade\r\n"
        response += "Sec-WebSocket-Accept: \(accept)\r\n"
        if let origin = origin, Self.isAllowedOrigin(origin) {
            response += "Access-Control-Allow-Origin: \(origin)\r\n"
        }
        response += "\r\n"

        phase = .webSocket
        webSocketOpen = true
        server?.registerWebSocketSession(self)
        connection.send(content: Data(response.utf8), completion: .contentProcessed { [weak self] error in
            guard let self = self else { return }
            if error != nil {
                self.finish()
                return
            }
            self.receiveWebSocket()
        })
    }

    private func receiveWebSocket() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if error != nil {
                self.finish()
                return
            }
            if let data = data, !data.isEmpty {
                self.wsBuffer.append(contentsOf: data)
                self.processWebSocketFrames()
            }
            if isComplete {
                self.finish()
                return
            }
            if self.webSocketOpen {
                self.receiveWebSocket()
            }
        }
    }

    private func processWebSocketFrames() {
        while webSocketOpen {
            guard wsBuffer.count >= 2 else { return }
            let first = wsBuffer[0]
            let second = wsBuffer[1]
            let fin = (first & 0x80) != 0
            let opcode = first & 0x0F
            let masked = (second & 0x80) != 0
            var length = Int(second & 0x7F)
            var offset = 2
            if length == 126 {
                guard wsBuffer.count >= offset + 2 else { return }
                length = (Int(wsBuffer[offset]) << 8) | Int(wsBuffer[offset + 1])
                offset += 2
            } else if length == 127 {
                guard wsBuffer.count >= offset + 8 else { return }
                var value: UInt64 = 0
                for index in 0..<8 {
                    value = (value << 8) | UInt64(wsBuffer[offset + index])
                }
                if value > UInt64(Limits.wsMaxFrameBytes) {
                    closeWebSocket(code: 1009)
                    return
                }
                length = Int(value)
                offset += 8
            }
            let maskLength = masked ? 4 : 0
            guard wsBuffer.count >= offset + maskLength + length else { return }
            var payload = Array(wsBuffer[(offset + maskLength)..<(offset + maskLength + length)])
            if masked {
                let mask = Array(wsBuffer[offset..<(offset + 4)])
                for index in 0..<payload.count {
                    payload[index] ^= mask[index % 4]
                }
            }
            wsBuffer.removeFirst(offset + maskLength + length)

            switch opcode {
            case 0x9: // ping → pong
                sendFrame(opcode: 0xA, payload: Data(payload))
            case 0xA: // pong
                break
            case 0x8: // close
                sendFrame(opcode: 0x8, payload: Data())
                finish()
                return
            case 0x1, 0x2:
                wsAssembledOpcode = opcode
                wsAssembled.append(contentsOf: payload)
            case 0x0:
                wsAssembled.append(contentsOf: payload)
            default:
                break
            }

            if fin, (opcode == 0x0 || opcode == 0x1 || opcode == 0x2) {
                let message = wsAssembled
                wsAssembled = Data()
                if wsEcho {
                    sendFrame(opcode: wsAssembledOpcode == 0x1 ? 0x1 : 0x2, payload: message)
                }
                // 非 echo 模式：这里就是后续开发者接入业务逻辑的扩展点
            }
        }
    }

    /// 供 server.broadcast 调用的二进制帧发送。
    func sendBinaryFrame(_ payload: Data) {
        guard webSocketOpen else { return }
        sendFrame(opcode: 0x2, payload: payload)
    }

    private func sendFrame(opcode: UInt8, payload: Data) {
        guard webSocketOpen else { return }
        var frame = Data()
        frame.append(0x80 | (opcode & 0x0F))
        let length = payload.count
        if length <= 125 {
            frame.append(UInt8(length))
        } else if length <= 65535 {
            frame.append(126)
            frame.append(UInt8((length >> 8) & 0xFF))
            frame.append(UInt8(length & 0xFF))
        } else {
            frame.append(127)
            let value = UInt64(length)
            for shift in stride(from: 56, through: 0, by: -8) {
                frame.append(UInt8((value >> UInt64(shift)) & 0xFF))
            }
        }
        frame.append(payload)
        connection.send(content: frame, completion: .contentProcessed { _ in })
    }

    private func closeWebSocket(code: UInt16) {
        var payload = Data()
        payload.append(UInt8((code >> 8) & 0xFF))
        payload.append(UInt8(code & 0xFF))
        sendFrame(opcode: 0x8, payload: payload)
        finish()
    }

    private static func wsAccept(_ key: String) -> String? {
        let magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        guard let data = (key + magic).data(using: .utf8) else { return nil }
        let digest = Insecure.SHA1.hash(data: data)
        return Data(digest).base64EncodedString()
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
        case ("GET", "/info"):
            handleInfo(origin: origin)
        case ("GET", "/list"):
            handleList(origin: origin)
        case ("DELETE", "/file"), ("POST", "/delete"):
            handleDelete(origin: origin)
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

    private func handleInfo(origin: String?) {
        guard let file = try? resolveSafePath(query["path"]) else {
            sendAndClose(status: 400, reason: "Bad Request", json: ["error": "invalid path"], origin: origin)
            return
        }
        let exists = FileManager.default.fileExists(atPath: file.path)
        var isDirectory: ObjCBool = false
        if exists {
            FileManager.default.fileExists(atPath: file.path, isDirectory: &isDirectory)
        }
        let attributes = exists ? (try? FileManager.default.attributesOfItem(atPath: file.path)) : nil
        let size = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
        let body: [String: Any] = [
            "path": query["path"] ?? "",
            "exists": exists,
            "isDir": isDirectory.boolValue,
            "size": size,
            "mimeType": isDirectory.boolValue ? "" : Self.mimeType(for: file.lastPathComponent)
        ]
        sendAndClose(status: 200, reason: "OK", json: body, origin: origin)
    }

    private func handleList(origin: String?) {
        let rel = query["path"] ?? ""
        guard let dir = try? resolveSafePath(rel) else {
            sendAndClose(status: 400, reason: "Bad Request", json: ["error": "invalid path"], origin: origin)
            return
        }
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: dir.path, isDirectory: &isDirectory), isDirectory.boolValue else {
            sendAndClose(status: 404, reason: "Not Found", json: ["error": "not a directory"], origin: origin)
            return
        }
        let keys: [URLResourceKey] = [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey]
        let contents = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys)) ?? []
        let sorted = contents.sorted { $0.lastPathComponent < $1.lastPathComponent }
        var entries: [[String: Any]] = []
        for url in sorted {
            let values = try? url.resourceValues(forKeys: Set(keys))
            entries.append([
                "name": url.lastPathComponent,
                "isDir": values?.isDirectory ?? false,
                "size": values?.fileSize ?? 0,
                "lastModified": Int64((values?.contentModificationDate?.timeIntervalSince1970 ?? 0) * 1000)
            ])
        }
        sendAndClose(status: 200, reason: "OK", json: entries, origin: origin)
    }

    private func handleDelete(origin: String?) {
        guard let file = try? resolveSafePath(query["path"]) else {
            sendAndClose(status: 400, reason: "Bad Request", json: ["error": "invalid path"], origin: origin)
            return
        }
        if file.standardizedFileURL.path == root.standardizedFileURL.path {
            sendAndClose(status: 400, reason: "Bad Request", json: ["error": "cannot delete root"], origin: origin)
            return
        }
        guard FileManager.default.fileExists(atPath: file.path) else {
            sendAndClose(status: 404, reason: "Not Found", json: ["error": "not found"], origin: origin)
            return
        }
        do {
            try FileManager.default.removeItem(at: file)
            sendAndClose(status: 200, reason: "OK", json: ["deleted": true], origin: origin)
        } catch {
            sendAndClose(status: 500, reason: "Internal Server Error", json: ["error": "delete failed"], origin: origin)
        }
    }

    // MARK: 安全

    private func isAuthorized() -> Bool {
        var provided: String?
        if let auth = headers["authorization"], auth.lowercased().hasPrefix("bearer ") {
            provided = String(auth.dropFirst(7)).trimmingCharacters(in: .whitespaces)
        }
        if provided == nil || provided?.isEmpty == true {
            provided = query["token"]
        }
        guard let value = provided, !value.isEmpty else { return false }
        let providedBytes = Array(value.utf8)
        let expectedBytes = Array(token.utf8)
        guard providedBytes.count == expectedBytes.count else { return false }
        var diff: UInt8 = 0
        for index in 0..<expectedBytes.count {
            diff |= providedBytes[index] ^ expectedBytes[index]
        }
        return diff == 0
    }

    private func resolveSafePath(_ relPath: String?) throws -> URL {
        let raw = (relPath ?? "").trimmingCharacters(in: .whitespaces)
        if raw.isEmpty {
            return root
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
        CAPLog.print("[DataChannel] respond \(status) \(reason)")
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
            lines += "Access-Control-Allow-Methods: GET, PUT, POST, DELETE, OPTIONS\r\n"
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
        if webSocketOpen {
            webSocketOpen = false
            server?.unregisterWebSocketSession(self)
        }
        try? uploadHandle?.close()
        uploadHandle = nil
        connection.cancel()
        onFinish()
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
