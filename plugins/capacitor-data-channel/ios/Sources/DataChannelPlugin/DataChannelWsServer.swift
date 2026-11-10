import Foundation
import Network
import Capacitor
import CryptoKit

/// DataChannel **WebSocket** Server（iOS，NWListener，独立端口）。
///
/// 与 HTTP server 完全独立：独立端口、独立 OTP 表、独立连接限流、可单独启停。
///
/// 握手鉴权（一次性 OTP；WS 双向含写入，不允许 reusable）：
///   - `Sec-WebSocket-Protocol: otp.<otp>`（推荐，不进 URL）
///   - 或 `?otp=<otp>`
///   - 校验失败返回 404；成功后 101 回显子协议
///
/// 默认 echo；支持文本/二进制帧、分片重组、ping/pong、close；单帧上限 64MB。
final class DataChannelWsServer {

    enum ServerError: Error {
        case listenFailed(String)
        case timeout
    }

    private let otpStore: OtpStore
    private let wsEcho: Bool
    private let limiter: ConnectionLimiter
    private let queue = DispatchQueue(label: "capacitor.datachannel.ws")
    private let stateLock = NSLock()
    private let sessionsLock = NSLock()
    private var sessions: [WSConnection] = []

    private var listener: NWListener?
    private var running = false
    private var boundPort: UInt16 = 0

    init(otpStore: OtpStore, wsEcho: Bool, maxConnections: Int) {
        self.otpStore = otpStore
        self.wsEcho = wsEcho
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

    var clientCount: Int {
        sessionsLock.lock()
        defer { sessionsLock.unlock() }
        return sessions.count
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

        if semaphore.wait(timeout: .now() + 5) == .timedOut {
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

        sessionsLock.lock()
        let pending = sessions
        sessionsLock.unlock()
        for session in pending {
            session.shutdown()
        }
    }

    /// 向所有客户端广播二进制帧（供 native 侧扩展使用）。
    @discardableResult
    func broadcast(_ payload: Data) -> Int {
        sessionsLock.lock()
        let current = sessions
        sessionsLock.unlock()
        for session in current {
            session.sendBinaryFrame(payload)
        }
        return current.count
    }

    // MARK: - 连接

    private func accept(_ connection: NWConnection) {
        guard limiter.tryAcquirePending() else {
            CAPLog.print("[DataChannelWs] reject: pending limit reached")
            connection.cancel()
            return
        }
        #if !targetEnvironment(simulator)
        guard case let .hostPort(host, _) = connection.endpoint else {
            connection.cancel()
            limiter.release(authenticated: false)
            return
        }
        let hostString = "\(host)".lowercased()
        if hostString != "127.0.0.1" && hostString != "::1" && hostString != "localhost" {
            CAPLog.print("[DataChannelWs] reject: non-loopback host \(hostString)")
            connection.cancel()
            limiter.release(authenticated: false)
            return
        }
        #endif

        let session = WSConnection(
            connection: connection,
            otpStore: otpStore,
            wsEcho: wsEcho,
            limiter: limiter,
            server: self,
            queue: queue
        )
        registerSession(session)
        session.start()
    }

    fileprivate func registerSession(_ session: WSConnection) {
        sessionsLock.lock()
        sessions.append(session)
        sessionsLock.unlock()
    }

    fileprivate func unregisterSession(_ session: WSConnection) {
        sessionsLock.lock()
        sessions.removeAll { $0 === session }
        sessionsLock.unlock()
    }
}

// MARK: - 单个 WebSocket 连接

private final class WSConnection {

    private enum Phase {
        case handshake
        case frames
        case done
    }

    private enum Limits {
        static let maxHeaderBytes = 32 * 1024
        static let maxHeaderLines = 64
        static let maxFrameBytes = 64 * 1024 * 1024
        static let handshakeTimeout: TimeInterval = 10
    }

    private let connection: NWConnection
    private let otpStore: OtpStore
    private let wsEcho: Bool
    private let limiter: ConnectionLimiter
    private weak var server: DataChannelWsServer?
    private let queue: DispatchQueue

    private var phase: Phase = .handshake
    private var headerData = Data()
    private var method = ""
    private var path = "/"
    private var query: [String: String] = [:]
    private var headers: [String: String] = [:]
    private var finished = false
    private var authenticated = false
    private var open = false

    private var frameBuffer: [UInt8] = []
    private var assembled = Data()
    private var assembledOpcode: UInt8 = 0

    init(
        connection: NWConnection,
        otpStore: OtpStore,
        wsEcho: Bool,
        limiter: ConnectionLimiter,
        server: DataChannelWsServer,
        queue: DispatchQueue
    ) {
        self.connection = connection
        self.otpStore = otpStore
        self.wsEcho = wsEcho
        self.limiter = limiter
        self.server = server
        self.queue = queue
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.receiveHandshake()
            case .failed(let error):
                CAPLog.print("[DataChannelWs] connection failed: \(error)")
                self?.finish()
            case .cancelled:
                self?.finish()
            default:
                break
            }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + Limits.handshakeTimeout) { [weak self] in
            guard let self = self, !self.finished, self.phase == .handshake else { return }
            self.sendSimpleResponse(status: 408, reason: "Request Timeout")
            self.finish()
        }
    }

    func shutdown() {
        finish()
    }

    // MARK: 握手

    private func receiveHandshake() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if error != nil {
                self.finish()
                return
            }
            if let data = data, !data.isEmpty {
                self.headerData.append(data)
            }
            if let headerEnd = self.headerData.range(of: Data("\r\n\r\n".utf8)) {
                let headerPart = self.headerData[..<headerEnd.lowerBound]
                self.headerData = Data()
                self.handleHandshake(headerPart)
                return
            }
            if self.headerData.count > Limits.maxHeaderBytes {
                self.sendSimpleResponse(status: 431, reason: "Request Header Fields Too Large")
                self.finish()
                return
            }
            if isComplete {
                self.finish()
                return
            }
            self.receiveHandshake()
        }
    }

    private func handleHandshake(_ data: Data) {
        guard let text = String(data: data, encoding: .isoLatin1) else {
            sendSimpleResponse(status: 400, reason: "Bad Request")
            finish()
            return
        }
        let lines = text.components(separatedBy: "\r\n")
        guard lines.count <= Limits.maxHeaderLines, let requestLine = lines.first else {
            sendSimpleResponse(status: 400, reason: "Bad Request")
            finish()
            return
        }
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else {
            sendSimpleResponse(status: 400, reason: "Bad Request")
            finish()
            return
        }
        method = parts[0].uppercased()
        let target = String(parts[1])
        guard let url = URL(string: target.hasPrefix("/") ? target : "/" + target) else {
            sendSimpleResponse(status: 400, reason: "Bad Request")
            finish()
            return
        }
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

        let origin = headers["origin"]
        if let origin = origin, !DataChannelOrigins.allows(origin) {
            CAPLog.print("[DataChannelWs] dropped: disallowed origin")
            finish()
            return
        }
        guard path == "/ws", (headers["upgrade"] ?? "").lowercased() == "websocket", let key = headers["sec-websocket-key"], !key.isEmpty else {
            sendSimpleResponse(status: 404, reason: "Not Found")
            finish()
            return
        }

        // OTP（一次性；WS 双向含写入，不允许 reusable）
        let otp = extractOtp()
        guard otpStore.consume(otp, scope: OtpStore.scopeReadWrite, path: ""),
              limiter.promoteToActive() else {
            sendSimpleResponse(status: 404, reason: "Not Found")
            finish()
            return
        }
        authenticated = true

        guard let accept = Self.wsAccept(key) else {
            finish()
            return
        }
        let usedOtp = otp ?? ""
        var handshake = "HTTP/1.1 101 Switching Protocols\r\n"
        handshake += "Upgrade: websocket\r\n"
        handshake += "Connection: Upgrade\r\n"
        handshake += "Sec-WebSocket-Accept: \(accept)\r\n"
        handshake += "Sec-WebSocket-Protocol: otp.\(usedOtp)\r\n"
        if let origin = origin, DataChannelOrigins.allows(origin) {
            handshake += "Access-Control-Allow-Origin: \(origin)\r\n"
        }
        handshake += "\r\n"

        phase = .frames
        open = true
        connection.send(content: Data(handshake.utf8), completion: .contentProcessed { [weak self] error in
            guard let self = self else { return }
            if error != nil {
                self.finish()
                return
            }
            CAPLog.print("[DataChannelWs] client connected (echo=\(self.wsEcho))")
            // 握手阶段可能已经带上来数据（极少），先处理再继续收
            self.processFrames()
            if self.open {
                self.receiveFrames()
            }
        })
    }

    private func extractOtp() -> String? {
        if let protocolHeader = headers["sec-websocket-protocol"] {
            for item in protocolHeader.split(separator: ",") {
                let candidate = item.trimmingCharacters(in: .whitespaces)
                if candidate.hasPrefix("otp.") {
                    return String(candidate.dropFirst(4))
                }
            }
        }
        return query["otp"]
    }

    // MARK: 帧循环

    private func receiveFrames() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if error != nil {
                self.finish()
                return
            }
            if let data = data, !data.isEmpty {
                self.frameBuffer.append(contentsOf: data)
                self.processFrames()
            }
            if isComplete {
                self.finish()
                return
            }
            if self.open {
                self.receiveFrames()
            }
        }
    }

    private func processFrames() {
        while open {
            guard frameBuffer.count >= 2 else { return }
            let first = frameBuffer[0]
            let second = frameBuffer[1]
            let fin = (first & 0x80) != 0
            let opcode = first & 0x0F
            let masked = (second & 0x80) != 0
            var length = Int(second & 0x7F)
            var offset = 2
            if length == 126 {
                guard frameBuffer.count >= offset + 2 else { return }
                length = (Int(frameBuffer[offset]) << 8) | Int(frameBuffer[offset + 1])
                offset += 2
            } else if length == 127 {
                guard frameBuffer.count >= offset + 8 else { return }
                var value: UInt64 = 0
                for index in 0..<8 {
                    value = (value << 8) | UInt64(frameBuffer[offset + index])
                }
                if value > UInt64(Limits.maxFrameBytes) {
                    closeFrame(code: 1009)
                    return
                }
                length = Int(value)
                offset += 8
            }
            let maskLength = masked ? 4 : 0
            guard frameBuffer.count >= offset + maskLength + length else { return }
            var payload = Array(frameBuffer[(offset + maskLength)..<(offset + maskLength + length)])
            if masked {
                let mask = Array(frameBuffer[offset..<(offset + 4)])
                for index in 0..<payload.count {
                    payload[index] ^= mask[index % 4]
                }
            }
            frameBuffer.removeFirst(offset + maskLength + length)

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
                assembledOpcode = opcode
                assembled.append(contentsOf: payload)
            case 0x0:
                assembled.append(contentsOf: payload)
            default:
                break
            }

            if fin, (opcode == 0x0 || opcode == 0x1 || opcode == 0x2) {
                let message = assembled
                assembled = Data()
                handleCompleteMessage(opcode: assembledOpcode == 0x1 ? 0x1 : 0x2, message: message)
            }
        }
    }

    /// 处理一条完整消息（自定义协议）：
    ///
    ///   正常：[OTP 字符串(43 字符 ASCII)][0x00][payload 原始二进制]
    ///   错误：[0x00][错误文本 UTF-8]（Server→Client）
    ///
    /// - 按**第一个 0x00** 分割（OTP 是 base64url，永远不含 0x00；payload 可含 0x00）；
    /// - OTP 校验失败 → 回错误消息，不回显数据；
    /// - 校验成功 → （echo 模式）生成新 nonce，回 `[nonce][0x00][payload]`，
    ///   JS 需用 `validateAndConsumeOTP` 验证该 nonce。
    private func handleCompleteMessage(opcode: UInt8, message: Data) {
        guard let separator = message.firstIndex(of: 0) else {
            sendError("missing OTP separator (0x00)")
            return
        }
        let offset = message.distance(from: message.startIndex, to: separator)
        if offset == 0 {
            sendError("missing OTP")
            return
        }
        let otpBytes = message[message.startIndex..<separator]
        guard let otp = String(data: Data(otpBytes), encoding: .ascii) else {
            sendError("invalid OTP encoding")
            return
        }
        let payload = message.suffix(from: message.index(after: separator))
        guard otpStore.consume(otp, scope: OtpStore.scopeReadWrite, path: "") else {
            CAPLog.print("[DataChannelWs] drop message: invalid or reused OTP")
            sendError("invalid or reused OTP")
            return
        }
        if !wsEcho {
            // 非 echo 模式：这里就是后续开发者接入业务逻辑的扩展点（payload 已校验过 OTP）
            return
        }
        let nonce: String
        do {
            nonce = try otpStore.create(scope: OtpStore.scopeReadWrite, path: "", reusable: false)
        } catch {
            CAPLog.print("[DataChannelWs] cannot echo: \(error.localizedDescription)")
            sendError("too many pending nonces")
            return
        }
        var out = Data(nonce.utf8)
        out.append(0)
        out.append(Data(payload))
        sendFrame(opcode: opcode == 0x1 ? 0x1 : 0x2, payload: out)
    }

    /// 回一条错误消息：`[0x00][错误文本 UTF-8]`（二进制帧，便于客户端统一按 ArrayBuffer 处理）。
    private func sendError(_ error: String) {
        var out = Data([0])
        out.append(Data(error.utf8))
        sendFrame(opcode: 0x2, payload: out)
    }

    func sendBinaryFrame(_ payload: Data) {
        guard open else { return }
        sendFrame(opcode: 0x2, payload: payload)
    }

    private func sendFrame(opcode: UInt8, payload: Data) {
        guard open else { return }
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

    private func closeFrame(code: UInt16) {
        var payload = Data()
        payload.append(UInt8((code >> 8) & 0xFF))
        payload.append(UInt8(code & 0xFF))
        sendFrame(opcode: 0x8, payload: payload)
        finish()
    }

    private func sendSimpleResponse(status: Int, reason: String) {
        let response =
            "HTTP/1.1 \(status) \(reason)\r\n" +
            "Connection: close\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"
        connection.send(content: Data(response.utf8), completion: .contentProcessed { _ in })
    }

    private func finish() {
        if finished {
            return
        }
        finished = true
        open = false
        phase = .done
        connection.cancel()
        server?.unregisterSession(self)
        limiter.release(authenticated: authenticated)
    }

    // MARK: 工具

    private static func isAllowedOrigin(_ origin: String) -> Bool {
        DataChannelOrigins.allows(origin)
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

    private static func wsAccept(_ key: String) -> String? {
        let magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        guard let data = (key + magic).data(using: .utf8) else { return nil }
        let digest = Insecure.SHA1.hash(data: data)
        return Data(digest).base64EncodedString()
    }
}
