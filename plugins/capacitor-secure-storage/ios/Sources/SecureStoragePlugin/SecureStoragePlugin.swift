import Capacitor
import Foundation
import LocalAuthentication
import Security

/**
 * 安全加密存储（iOS 侧）：Keychain Services。
 *
 * - 普通条目：`kSecClassGenericPassword` + `kSecAttrAccessible`（默认 WhenUnlockedThisDeviceOnly）；
 * - `requireBiometric: true`：条目带 `SecAccessControl(.biometryCurrentSet)`，
 *   读取时系统**自动**弹出 Face ID / Touch ID 面板（不需要自己调 evaluatePolicy）；
 *   重新录入面容/指纹会让该条目永久无法读取（最强语义）。
 *
 * 键名清单另外存一份在 UserDefaults 里，这样 `keys()` 不需要碰 Keychain（不会触发认证）。
 * 注意：Keychain 条目在卸载 App 后仍然保留，而 UserDefaults 会被清空 —— 重装后 `keys()` 为空
 * 但旧数据仍在，这是 Apple 的设计（README 有说明）。
 */
@objc(SecureStoragePlugin)
public class SecureStoragePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "SecureStoragePlugin"
    public let jsName = "SecureStorage"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "set", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "get", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "remove", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "keys", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clear", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isBiometryAvailable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "authenticate", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getInfo", returnType: CAPPluginReturnPromise)
    ]

    private let service = "com.testionic.securestorage"
    private let indexKey = "capacitorSecureStorage.index"

    // MARK: - 索引（key -> 是否需要生物识别）

    private func readIndex() -> [String: Bool] {
        UserDefaults.standard.dictionary(forKey: indexKey) as? [String: Bool] ?? [:]
    }

    private func writeIndex(_ index: [String: Bool]) {
        UserDefaults.standard.set(index, forKey: indexKey)
    }

    private func setIndexFlag(_ key: String, bio: Bool) {
        var index = readIndex()
        index[key] = bio
        writeIndex(index)
    }

    // MARK: - Keychain 基础

    private func baseQuery(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
    }

    private func accessibility(_ name: String?) -> CFString {
        switch name {
        case "whenUnlocked": return kSecAttrAccessibleWhenUnlocked
        case "afterFirstUnlock": return kSecAttrAccessibleAfterFirstUnlock
        case "afterFirstUnlockThisDeviceOnly": return kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        case "whenPasscodeSetThisDeviceOnly": return kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
        case "whenUnlockedThisDeviceOnly": return kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        default: return kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        }
    }

    private func makeAccessControl() -> SecAccessControl? {
        var error: Unmanaged<CFError>?
        let control = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            .biometryCurrentSet,
            &error
        )
        if let error = error {
            CAPLog.print("⚡️ SecureStorage: SecAccessControl 创建失败 \(error.takeRetainedValue())")
            return nil
        }
        return control
    }

    /// 把 `LABiometryType` 映射成 JS 侧的字符串类型（`.opticID` 需要 iOS 17 的可用性判断）。
    private func biometryTypeName(_ type: LABiometryType) -> String {
        switch type {
        case .faceID:
            return "face"
        case .touchID:
            return "fingerprint"
        default:
            if #available(iOS 17.0, *), type == .opticID {
                return "iris"
            }
            return "biometric"
        }
    }

    private func keychainErrorMessage(_ status: OSStatus) -> String? {
        switch status {
        case errSecSuccess:
            return nil
        case errSecItemNotFound:
            return "NOT_FOUND"
        case errSecUserCanceled:
            return "USER_CANCELED: 用户取消了生物识别"
        case errSecAuthFailed:
            return "AUTH_FAILED: 生物识别验证失败（或连续失败被锁定）"
        case errSecInteractionNotAllowed:
            return "INTERACTION_NOT_ALLOWED: 当前状态下无法弹出认证界面（设备已锁定 / App 在后台）"
        case errSecDuplicateItem:
            return "DUPLICATE_ITEM"
        case errSecMissingEntitlement:
            return "MISSING_ENTITLEMENT"
        default:
            let message = SecCopyErrorMessageString(status, nil) as String? ?? "unknown"
            return "KEYCHAIN_ERROR(\(status)): \(message)"
        }
    }

    // MARK: - API

    @objc public func set(_ call: CAPPluginCall) {
        guard let key = call.getString("key"), !key.isEmpty else {
            call.reject("key 不能为空", "INVALID_KEY")
            return
        }
        guard let value = call.getString("value") else {
            call.reject("value 不能为 null（删除请用 remove）", "INVALID_VALUE")
            return
        }
        let requireBiometric = call.getBool("requireBiometric", false)
        guard let data = value.data(using: .utf8) else {
            call.reject("value 无法编码为 UTF-8", "INVALID_VALUE")
            return
        }

        if requireBiometric {
            let context = LAContext()
            var authError: NSError?
            let canUse = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &authError)
            if !canUse {
                let code = (authError?.code == LAError.biometryNotEnrolled.rawValue)
                    ? "BIOMETRY_NOT_ENROLLED"
                    : "BIOMETRY_UNAVAILABLE"
                call.reject("\(code): 设备未录入或不可用生物识别", code)
                return
            }
        }

        // 先删掉旧条目：ACL 变更时 SecItemUpdate 不会更新 ACL，必须重建
        SecItemDelete(baseQuery(key) as CFDictionary)

        var attributes = baseQuery(key)
        attributes[kSecValueData as String] = data
        if requireBiometric, let control = makeAccessControl() {
            // 有 ACL 时不能再设置 kSecAttrAccessible（可访问性已写进 ACL）
            attributes[kSecAttrAccessControl as String] = control
        } else {
            attributes[kSecAttrAccessible as String] = accessibility(call.getString("accessibility"))
        }

        let status = SecItemAdd(attributes as CFDictionary, nil)
        if let error = keychainErrorMessage(status) {
            let code = error.split(separator: ":").first.map(String.init) ?? "KEYCHAIN_ERROR"
            call.reject(error, code)
            return
        }
        setIndexFlag(key, bio: requireBiometric)
        call.resolve(["key": key, "requireBiometric": requireBiometric])
    }

    @objc public func get(_ call: CAPPluginCall) {
        guard let key = call.getString("key"), !key.isEmpty else {
            call.reject("key 不能为空", "INVALID_KEY")
            return
        }
        let indexBio = readIndex()[key] ?? false
        let result = readItem(key: key, bio: indexBio, reason: call.getString("reason"))
        switch result {
        case .success(let data):
            let value = String(data: data, encoding: .utf8) ?? ""
            call.resolve(["key": key, "value": value, "requireBiometric": indexBio, "notFound": false])
        case .notFound:
            call.resolve(["key": key, "value": NSNull(), "requireBiometric": indexBio, "notFound": true])
        case .needsBiometric:
            // 索引说"不需要"，但条目实际带 ACL（例如重装后索引丢失）→ 带上下文重试并自愈索引
            let retry = readItem(key: key, bio: true, reason: call.getString("reason"))
            switch retry {
            case .success(let data):
                setIndexFlag(key, bio: true)
                let value = String(data: data, encoding: .utf8) ?? ""
                call.resolve(["key": key, "value": value, "requireBiometric": true, "notFound": false])
            case .failure(let message, let code):
                call.reject(message, code)
            case .notFound:
                call.resolve(["key": key, "value": NSNull(), "requireBiometric": true, "notFound": true])
            case .needsBiometric:
                call.reject("BIOMETRY_REQUIRED: 该项需要生物识别验证", "BIOMETRY_REQUIRED")
            }
        case .failure(let message, let code):
            call.reject(message, code)
        }
    }

    private enum ReadResult {
        case success(Data)
        case notFound
        case needsBiometric
        case failure(String, String)
    }

    private func readItem(key: String, bio: Bool, reason: String?) -> ReadResult {
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        if bio {
            let localContext = LAContext()
            localContext.localizedReason = reason ?? "验证身份以读取加密数据"
            query[kSecUseAuthenticationContext as String] = localContext
        }
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)

        switch status {
        case errSecSuccess:
            guard let data = item as? Data else {
                return .failure("CORRUPTED_DATA: Keychain 返回的不是 Data", "CORRUPTED_DATA")
            }
            return .success(data)
        case errSecItemNotFound:
            return .notFound
        case errSecInteractionNotAllowed, errSecAuthFailed:
            return bio ? .failure("AUTH_FAILED: 生物识别验证未通过", "AUTH_FAILED") : .needsBiometric
        case errSecUserCanceled:
            return .failure("USER_CANCELED: 用户取消了生物识别", "USER_CANCELED")
        default:
            let message = keychainErrorMessage(status) ?? "KEYCHAIN_ERROR(\(status))"
            let code = message.split(separator: ":").first.map(String.init) ?? "KEYCHAIN_ERROR"
            return .failure(message, code)
        }
    }

    @objc public func remove(_ call: CAPPluginCall) {
        guard let key = call.getString("key"), !key.isEmpty else {
            call.reject("key 不能为空", "INVALID_KEY")
            return
        }
        let existed = (readIndex()[key] != nil)
        let status = SecItemDelete(baseQuery(key) as CFDictionary)
        if status != errSecSuccess && status != errSecItemNotFound {
            call.reject(keychainErrorMessage(status) ?? "KEYCHAIN_ERROR(\(status))", "KEYCHAIN_ERROR")
            return
        }
        var index = readIndex()
        index.removeValue(forKey: key)
        writeIndex(index)
        call.resolve(["key": key, "deleted": existed || status == errSecSuccess])
    }

    @objc public func keys(_ call: CAPPluginCall) {
        call.resolve(["keys": Array(readIndex().keys).sorted()])
    }

    @objc public func clear(_ call: CAPPluginCall) {
        let index = readIndex()
        // 按 service 整体删除（比逐个 key 更彻底，能清掉索引丢失后的残留条目）
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service
        ]
        let status = SecItemDelete(query as CFDictionary)
        if status != errSecSuccess && status != errSecItemNotFound {
            call.reject(keychainErrorMessage(status) ?? "KEYCHAIN_ERROR(\(status))", "KEYCHAIN_ERROR")
            return
        }
        writeIndex([:])
        call.resolve(["cleared": index.count])
    }

    @objc public func isBiometryAvailable(_ call: CAPPluginCall) {
        let context = LAContext()
        var authError: NSError?
        let available = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &authError)
        let type = available ? biometryTypeName(context.biometryType) : "none"
        let enrolled = available || authError?.code != LAError.biometryNotEnrolled.rawValue
        var result: [String: Any] = [
            "available": available,
            "type": type,
            "enrolled": enrolled
        ]
        if let authError = authError {
            result["reason"] = "\(authError.code): \(authError.localizedDescription)"
        }
        call.resolve(result)
    }

    @objc public func authenticate(_ call: CAPPluginCall) {
        let context = LAContext()
        context.localizedCancelTitle = call.getString("cancelText", "取消")
        context.localizedFallbackTitle = ""
        let reason = call.getString("reason", "请验证身份")

        var authError: NSError?
        var policy = LAPolicy.deviceOwnerAuthenticationWithBiometrics
        if !context.canEvaluatePolicy(policy, error: &authError) {
            // 没有可用的生物识别时退回设备密码（与 `.userPresence` 语义一致）
            policy = .deviceOwnerAuthentication
        }
        context.evaluatePolicy(policy, localizedReason: reason) { success, error in
            if success {
                call.resolve(["success": true])
                return
            }
            let code = (error as NSError?)?.code ?? -1
            if code == LAError.userCancel.rawValue || code == LAError.appCancel.rawValue
                || code == LAError.systemCancel.rawValue || code == LAError.userFallback.rawValue {
                call.resolve(["success": false, "reason": "USER_CANCELED"])
            } else {
                call.resolve(["success": false, "reason": "\(code): \(error?.localizedDescription ?? "unknown")"])
            }
        }
    }

    @objc public func getInfo(_ call: CAPPluginCall) {
        let context = LAContext()
        var authError: NSError?
        let available = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &authError)
        let type = available ? biometryTypeName(context.biometryType) : "none"
        call.resolve([
            "platform": "ios",
            "backend": "Keychain (kSecClassGenericPassword)",
            "itemCount": readIndex().count,
            "biometry": [
                "available": available,
                "type": type,
                "enrolled": available || authError?.code != LAError.biometryNotEnrolled.rawValue
            ]
        ])
    }
}
