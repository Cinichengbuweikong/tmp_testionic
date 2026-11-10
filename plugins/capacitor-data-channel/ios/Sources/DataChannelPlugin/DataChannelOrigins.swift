import Foundation

/**
 * DataChannel 的 Origin 白名单。
 *
 * 默认只允许 Capacitor 的几种本地 origin；开发环境（Live Reload）下页面 origin 会变成
 * `http://<host>:<port>`（例如 `http://localhost:5173`），和默认列表里的
 * `http://localhost` **不相等**（带端口），不额外放行的话 HTTP/WS 会被静默断开。
 *
 * 插件在 `startHttp({ debug: true })` / `startWebSocket({ debug: true })` 时，
 * 会从**当前 WebView 的 URL** 推导出 dev origin 并调用 `setExtra(_:)` 放行。
 * 生产环境不要开 debug。
 */
enum DataChannelOrigins {
    /// 内置允许的 origin（与 Android 的 ALLOWED_ORIGINS 保持一致）。
    static let standard: Set<String> = [
        "capacitor://localhost",
        "https://localhost",
        "http://localhost",
        "ionic://localhost"
    ]

    private static let lock = NSLock()
    private static var extra: Set<String> = []

    /// 设置额外的放行 origin（传空集合即恢复默认）。
    static func setExtra(_ origins: Set<String>) {
        lock.lock()
        extra = origins
        lock.unlock()
    }

    /// 当前额外的放行 origin（调试用）。
    static func currentExtra() -> Set<String> {
        lock.lock()
        defer { lock.unlock() }
        return extra
    }

    /// 判断某个 Origin 是否允许。
    static func allows(_ origin: String) -> Bool {
        let normalized = origin.trimmingCharacters(in: .whitespaces).lowercased()
        if standard.contains(normalized) {
            return true
        }
        lock.lock()
        let extraSnapshot = extra
        lock.unlock()
        return extraSnapshot.contains(normalized)
    }

    /// 从 WebView 当前 URL 推导 origin（`scheme://host[:port]`）；失败返回 nil。
    static func origin(of url: URL?) -> String? {
        guard let url = url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return nil
        }
        components.path = ""
        components.query = nil
        components.fragment = nil
        guard let scheme = components.scheme?.lowercased(), let host = components.host?.lowercased() else {
            return nil
        }
        if let port = components.port {
            return "\(scheme)://\(host):\(port)"
        }
        return "\(scheme)://\(host)"
    }
}
