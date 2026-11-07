import Foundation
import Security

/// OTP（一次性凭证）存储（iOS）。
///
/// 与 Android 侧 `OtpStore.java` 行为一致：
/// - 一次性（默认）：用一次作废，无时间限制；
/// - 可复用（仅 read）：媒体持续读取用，**必须用完 revoke**；
/// - 写操作不允许 reusable；
/// - 无 TTL：等价于"server 生命周期内有效"。
final class OtpStore {

    static let scopeRead = "read"
    static let scopeWrite = "write"
    static let scopeReadWrite = "readwrite"

    private static let maxOtps = 256

    struct Record {
        let scope: String
        let path: String
        let reusable: Bool
    }

    enum OtpError: Error, LocalizedError {
        case invalid(String)

        var errorDescription: String? {
            switch self {
            case .invalid(let message):
                return message
            }
        }
    }

    private let lock = NSLock()
    private var otps: [String: Record] = [:]

    // MARK: - API

    func create(scope: String, path: String, reusable: Bool) throws -> String {
        let normalizedScope = try Self.normalizeScope(scope)
        let normalizedPath = try Self.normalizePath(path)
        if reusable && normalizedScope != Self.scopeRead {
            throw OtpError.invalid("reusable OTP is only allowed with scope=read (write must use a fresh one-time OTP)")
        }

        lock.lock()
        defer { lock.unlock() }
        if otps.count >= Self.maxOtps {
            throw OtpError.invalid("too many active OTPs (max \(Self.maxOtps)); revoke unused ones first")
        }
        let otp = Self.randomToken()
        otps[otp] = Record(scope: normalizedScope, path: normalizedPath, reusable: reusable)
        return otp
    }

    /// 批量生成 N 个 OTP（一次桥调用）。
    ///
    /// Capacitor 的插件调用是串行的（iOS 上是串行 `DispatchQueue(label: "bridge")`），
    /// 每条消息单独 createWsOtp 会把往返延迟累加；批量生成把 N 次调用压成 1 次。
    func createBatch(count: Int, scope: String, path: String, reusable: Bool) throws -> [String] {
        guard count >= 1 else {
            throw OtpError.invalid("count must be >= 1")
        }
        let normalizedScope = try Self.normalizeScope(scope)
        let normalizedPath = try Self.normalizePath(path)
        if reusable && normalizedScope != Self.scopeRead {
            throw OtpError.invalid("reusable OTP is only allowed with scope=read (write must use a fresh one-time OTP)")
        }

        lock.lock()
        defer { lock.unlock() }
        if otps.count + count > Self.maxOtps {
            throw OtpError.invalid(
                "not enough OTP capacity: active=\(otps.count), requested=\(count), max=\(Self.maxOtps)"
            )
        }
        var created: [String] = []
        created.reserveCapacity(count)
        for _ in 0..<count {
            let otp = Self.randomToken()
            otps[otp] = Record(scope: normalizedScope, path: normalizedPath, reusable: reusable)
            created.append(otp)
        }
        return created
    }

    /// 校验并消费 OTP。一次性 OTP 通过后立即删除（并发下也不会重放）。
    func consume(_ otp: String?, scope: String, path: String) -> Bool {
        guard let otp = otp, !otp.isEmpty else {
            return false
        }
        lock.lock()
        defer { lock.unlock() }
        guard let record = otps[otp] else {
            return false
        }
        let scopeOk = record.scope == Self.scopeReadWrite || record.scope == scope
        guard scopeOk else {
            return false
        }
        let normalizedRequestPath = (try? Self.normalizePath(path)) ?? ""
        if !record.path.isEmpty && record.path != normalizedRequestPath {
            return false
        }
        if record.reusable {
            return true
        }
        otps.removeValue(forKey: otp)
        return true
    }

    func revoke(_ otp: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return otps.removeValue(forKey: otp) != nil
    }

    @discardableResult
    func revokeAll() -> Int {
        lock.lock()
        defer { lock.unlock() }
        let count = otps.count
        otps.removeAll()
        return count
    }

    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return otps.count
    }

    // MARK: - 工具

    private static func normalizeScope(_ scope: String) throws -> String {
        let trimmed = scope.trimmingCharacters(in: .whitespaces).lowercased()
        if trimmed.isEmpty {
            return scopeRead
        }
        switch trimmed {
        case scopeRead, scopeWrite, scopeReadWrite:
            return trimmed
        default:
            throw OtpError.invalid("invalid scope: \(scope) (read|write|readwrite)")
        }
    }

    /// 规范化相对路径：去掉开头的 / 与反斜杠，拒绝 .. 穿越。
    static func normalizePath(_ path: String) throws -> String {
        var rel = path.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "\\", with: "/")
        while rel.hasPrefix("/") {
            rel.removeFirst()
        }
        for segment in rel.split(separator: "/", omittingEmptySubsequences: true) where segment == ".." {
            throw OtpError.invalid("invalid path: '..' not allowed")
        }
        return rel
    }

    /// 32 字节随机 token（base64url，无填充）。
    static func randomToken() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        if status != errSecSuccess {
            // 兜底（理论上不会发生）：用 UUID
            return UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        }
        var base64 = Data(bytes).base64EncodedString()
        base64 = base64.replacingOccurrences(of: "+", with: "-")
        base64 = base64.replacingOccurrences(of: "/", with: "_")
        base64 = base64.replacingOccurrences(of: "=", with: "")
        return base64
    }
}

/// 两级连接限流（与 Android `OtpStore.ConnectionLimiter` 一致）：
/// - 未认证连接上限 4（防扫描/DoS）；
/// - 已认证连接上限 maxActive（默认 16，保证视频多 Range 并发）。
final class ConnectionLimiter {

    private let lock = NSLock()
    private var pending = 0
    private var active = 0
    private let maxPending: Int
    private let maxActive: Int

    init(maxActive: Int) {
        self.maxActive = max(1, maxActive)
        self.maxPending = min(4, max(1, maxActive))
    }

    func tryAcquirePending() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if pending >= maxPending {
            return false
        }
        pending += 1
        return true
    }

    func promoteToActive() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if active >= maxActive {
            return false
        }
        active += 1
        pending = max(0, pending - 1)
        return true
    }

    func release(authenticated: Bool) {
        lock.lock()
        defer { lock.unlock() }
        if authenticated {
            active = max(0, active - 1)
        } else {
            pending = max(0, pending - 1)
        }
    }

    var pendingCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return pending
    }

    var activeCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return active
    }

    var maxActiveCount: Int {
        return maxActive
    }
}
