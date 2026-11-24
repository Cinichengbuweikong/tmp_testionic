import Capacitor
import CryptoKit
import Foundation
import UIKit

/**
 * 热更新（iOS）。
 *
 * Capacitor 在 iOS 上持久化 WebView 根路径的机制是 `KeyValueStore.standard["serverBasePath"]`，
 * 但**下次启动时它会忽略路径本身，只取最后一段目录名**，去
 * `Library/NoCloud/ionic_built_snapshots/<最后一段>` 找资源（Ionic Appflow 的约定）。
 * 所以本插件把 bundle 直接装到 `Library/NoCloud/ionic_built_snapshots/<bundleId>`，
 * 这样持久化的完整路径与它推导出的目录是同一个地方。
 *
 * 另外 Capacitor 在二进制版本变化（装了新 IPA）时会自动清空这个 key —— 与 Android 侧
 * 我们自己的"原生升级就丢弃 OTA"逻辑一致。
 */
@objc(LiveUpdatePlugin)
public class LiveUpdatePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "LiveUpdatePlugin"
    public let jsName = "LiveUpdate"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "configure", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getConfig", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkForUpdate", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "downloadAndInstall", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "notifyAppReady", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "rollback", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reset", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "verifyBundle", returnType: CAPPluginReturnPromise)
    ]

    private enum Keys {
        static let serverUrl = "lu_server_url"
        static let publicKey = "lu_public_key"
        static let allowUnsigned = "lu_allow_unsigned"
        static let channel = "lu_channel"
        static let appId = "lu_app_id"
        static let allowDowngrade = "lu_allow_downgrade"
        static let timeoutMs = "lu_timeout_ms"
        static let current = "lu_current"
        static let currentVersion = "lu_current_version"
        static let previous = "lu_previous"
        static let previousVersion = "lu_previous_version"
        static let pending = "lu_pending"
        static let bootAttempts = "lu_boot_attempts"
        static let lastCheck = "lu_last_check"
        static let installedBuild = "lu_installed_build"
        static let lastNote = "lu_last_note"
    }

    private let defaults = UserDefaults.standard
    private var lastCheck: [String: Any]?

    // MARK: - 路径

    private var snapshotsDir: URL {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        return library
            .appendingPathComponent("NoCloud")
            .appendingPathComponent("ionic_built_snapshots")
    }

    private var builtinPath: String {
        Bundle.main.bundlePath + "/public"
    }

    private func bundleDir(_ id: String) -> URL {
        snapshotsDir.appendingPathComponent(id)
    }

    // MARK: - 生命周期

    override public func load() {
        runStartupSelfHeal()
    }

    private func runStartupSelfHeal() {
        let build = (Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String) ?? "0"
        let storedBuild = defaults.string(forKey: Keys.installedBuild)
        if storedBuild == nil {
            defaults.set(build, forKey: Keys.installedBuild)
            defaults.set(0, forKey: Keys.bootAttempts)
            return
        }
        if storedBuild != build {
            // 装了新 IPA：丢弃 OTA 包，回内置资源
            let hadOta = defaults.string(forKey: Keys.current) != nil
            clearBundles()
            clearState()
            KeyValueStore.standard["serverBasePath"] = nil as String?
            defaults.set(build, forKey: Keys.installedBuild)
            if hadOta {
                applyBasePath(builtinPath, reload: true)
            }
            return
        }

        guard defaults.bool(forKey: Keys.pending) else { return }
        let attempts = defaults.integer(forKey: Keys.bootAttempts) + 1
        defaults.set(attempts, forKey: Keys.bootAttempts)
        guard attempts >= 2 else { return }

        let note = "新包连续 \(attempts) 次启动未能确认（可能白屏/JS 崩溃），已自动回滚。"
        var targetPath = builtinPath
        var targetVersion = builtinVersion
        var targetId: String?
        if let previous = defaults.string(forKey: Keys.previous) {
            let dir = bundleDir(previous)
            if FileManager.default.fileExists(atPath: dir.path) {
                targetPath = dir.path
                targetVersion = defaults.string(forKey: Keys.previousVersion) ?? previous
                targetId = previous
            }
        }
        defaults.set(false, forKey: Keys.pending)
        defaults.set(0, forKey: Keys.bootAttempts)
        if let id = targetId {
            defaults.set(id, forKey: Keys.current)
            defaults.set(targetVersion, forKey: Keys.currentVersion)
            defaults.removeObject(forKey: Keys.previous)
            defaults.removeObject(forKey: Keys.previousVersion)
        } else {
            clearState()
        }
        defaults.set(note, forKey: Keys.lastNote)
        applyBasePath(targetPath, reload: true)
    }

    // MARK: - 配置

    @objc public func configure(_ call: CAPPluginCall) {
        guard let serverUrl = call.getString("serverUrl"), !serverUrl.isEmpty else {
            call.reject("serverUrl 不能为空", "INVALID_CONFIG")
            return
        }
        defaults.set(serverUrl, forKey: Keys.serverUrl)
        if let publicKey = call.getString("publicKey") {
            defaults.set(publicKey, forKey: Keys.publicKey)
        }
        if let allowUnsigned = call.getBool("allowUnsigned") {
            defaults.set(allowUnsigned, forKey: Keys.allowUnsigned)
        }
        if let channel = call.getString("channel") {
            defaults.set(channel, forKey: Keys.channel)
        }
        if let appId = call.getString("appId") {
            defaults.set(appId, forKey: Keys.appId)
        }
        if let allowDowngrade = call.getBool("allowDowngrade") {
            defaults.set(allowDowngrade, forKey: Keys.allowDowngrade)
        }
        if let timeout = call.getInt("timeoutMs") {
            defaults.set(timeout, forKey: Keys.timeoutMs)
        }
        call.resolve(configObject())
    }

    @objc public func getConfig(_ call: CAPPluginCall) {
        call.resolve(configObject())
    }

    private func configObject() -> [String: Any] {
        var preview: Any = NSNull()
        if let publicKey = defaults.string(forKey: Keys.publicKey) {
            let compact = publicKey.components(separatedBy: .whitespacesAndNewlines).joined()
            preview = String(compact.prefix(16)) + "..."
        }
        var serverUrl: Any = NSNull()
        if let value = defaults.string(forKey: Keys.serverUrl) {
            serverUrl = value
        }
        return [
            "serverUrl": serverUrl,
            "publicKeyPreview": preview,
            "allowUnsigned": defaults.bool(forKey: Keys.allowUnsigned),
            "channel": channel,
            "appId": appId,
            "allowDowngrade": defaults.bool(forKey: Keys.allowDowngrade),
            "timeoutMs": timeoutMs,
            "platform": "ios"
        ]
    }

    private var channel: String { defaults.string(forKey: Keys.channel) ?? "production" }
    private var appId: String {
        defaults.string(forKey: Keys.appId) ?? (Bundle.main.bundleIdentifier ?? "unknown")
    }
    private var timeoutMs: Int {
        let value = defaults.integer(forKey: Keys.timeoutMs)
        return value > 0 ? value : 30000
    }
    private var builtinVersion: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0"
    }

    // MARK: - 检查更新

    @objc public func checkForUpdate(_ call: CAPPluginCall) {
        // 网络请求可能几秒~30 秒：Capacitor 的插件调用走**串行**桥队列，
        // 在这里阻塞会把其它插件（Toast/状态栏/键盘…）的调用一起堵住。
        DispatchQueue.global(qos: .userInitiated).async { self.doCheckForUpdate(call) }
    }

    private func doCheckForUpdate(_ call: CAPPluginCall) {
        guard let serverUrl = call.getString("serverUrl") ?? defaults.string(forKey: Keys.serverUrl),
              !serverUrl.isEmpty else {
            call.reject("NOT_CONFIGURED: 请先调用 configure({ serverUrl })", "NOT_CONFIGURED")
            return
        }
        let channelName = call.getString("channel") ?? channel
        let force = call.getBool("force") ?? false
        let current = currentVersion

        var components = URLComponents(string: serverUrl.replacingOccurrences(of: "/+$", with: "", options: .regularExpression) + "/latest.json")
        components?.queryItems = [
            URLQueryItem(name: "appId", value: appId),
            URLQueryItem(name: "platform", value: "ios"),
            URLQueryItem(name: "channel", value: channelName),
            URLQueryItem(name: "version", value: current)
        ]
        guard let url = components?.url else {
            call.reject("INVALID_URL: serverUrl 不是合法地址", "INVALID_URL")
            return
        }

        do {
            let (data, response) = try fetchSync(url: url)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? -1
                call.reject("CHECK_FAILED: HTTP_\(code)", "CHECK_FAILED")
                return
            }
            guard let body = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                call.reject("CHECK_FAILED: 后端返回的不是 JSON 对象", "CHECK_FAILED")
                return
            }
            lastCheck = body
            defaults.set(Date().timeIntervalSince1970 * 1000, forKey: Keys.lastCheck)

            let version = body["version"] as? String ?? ""
            var result: [String: Any] = [
                "currentVersion": current,
                "version": version,
                "url": body["url"] as? String ?? NSNull(),
                "sha256": body["sha256"] as? String ?? NSNull(),
                "signature": body["signature"] as? String ?? NSNull(),
                "notes": body["notes"] as? String ?? NSNull(),
                "size": body["size"] as? Int ?? NSNull()
            ]
            if version.isEmpty {
                result["available"] = false
                result["reason"] = "UP_TO_DATE"
                call.resolve(result)
                return
            }
            if let minNative = body["minNativeVersion"] as? String,
               compareVersions(builtinVersion, minNative) < 0 {
                result["available"] = false
                result["reason"] = "NATIVE_TOO_OLD"
                call.resolve(result)
                return
            }
            let cmp = compareVersions(version, current)
            // 允许降级时，比当前旧的版本也当作"可安装"（默认拒绝，防降级攻击）
            let downgradeAllowed = call.getBool("allowDowngrade") ?? defaults.bool(forKey: Keys.allowDowngrade)
            if !(cmp > 0 || (cmp == 0 && version != current)) && !force && !(downgradeAllowed && cmp < 0) {
                result["available"] = false
                result["reason"] = cmp < 0 ? "DOWNGRADE_BLOCKED" : "UP_TO_DATE"
                call.resolve(result)
                return
            }
            result["available"] = true
            call.resolve(result)
        } catch {
            call.reject("CHECK_FAILED: \(error.localizedDescription)", "CHECK_FAILED")
        }
    }

    // MARK: - 安装

    @objc public func downloadAndInstall(_ call: CAPPluginCall) {
        let info = lastCheck
        guard let urlString = call.getString("url") ?? info?["url"] as? String,
              !urlString.isEmpty else {
            call.reject("NO_UPDATE_INFO: 请先 checkForUpdate() 或显式传入 url", "NO_UPDATE_INFO")
            return
        }
        guard let version = call.getString("version") ?? info?["version"] as? String,
              !version.isEmpty else {
            call.reject("VERSION_REQUIRED: 需要 version 来生成本地 bundle 目录", "VERSION_REQUIRED")
            return
        }
        guard let sha256 = call.getString("sha256") ?? info?["sha256"] as? String,
              !sha256.isEmpty else {
            call.reject("SHA256_REQUIRED: 必须校验包摘要", "SHA256_REQUIRED")
            return
        }
        let signature = call.getString("signature") ?? info?["signature"] as? String
        let allowUnsigned = defaults.bool(forKey: Keys.allowUnsigned)
        if !allowUnsigned {
            guard (defaults.string(forKey: Keys.publicKey) ?? "").isEmpty == false else {
                call.reject("SIGNATURE_KEY_MISSING: 未配置 publicKey；本地调试可用 allowUnsigned: true", "SIGNATURE_KEY_MISSING")
                return
            }
            guard let signature = signature, !signature.isEmpty else {
                call.reject("SIGNATURE_REQUIRED: 后端没有返回签名", "SIGNATURE_REQUIRED")
                return
            }
        }

        let bundleId = sanitize(version) + "-" + String(sha256.prefix(8))
        let staging = bundleDir(".staging-" + bundleId)
        let target = bundleDir(bundleId)
        let tempZip = FileManager.default.temporaryDirectory
            .appendingPathComponent("live-update-\(UUID().uuidString).zip")

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                guard let url = URL(string: urlString) else {
                    throw NSError(domain: "LiveUpdate", code: 1,
                                  userInfo: [NSLocalizedDescriptionKey: "INVALID_URL"])
                }
                let downloader = FileDownloader(onProgress: { bytes, total in
                    let percent = total > 0 ? (Double(bytes) * 1000 / Double(total)).rounded() / 10 : -1
                    self.notifyListeners("downloadProgress", data: [
                        "bytes": bytes, "total": total, "percent": percent
                    ])
                })
                try downloader.download(url: url, to: tempZip, timeout: TimeInterval(self.timeoutMs) / 1000.0)

                // 下载体积硬上限（512MB）：防止服务端无限流式返回把磁盘写满
                var fileSize: Int64 = 0
                if let attributes = try? FileManager.default.attributesOfItem(atPath: tempZip.path),
                   let bytes = attributes[.size] as? NSNumber {
                    fileSize = bytes.int64Value
                }
                guard fileSize <= 512 * 1024 * 1024 else {
                    throw NSError(domain: "LiveUpdate", code: 8, userInfo: [
                        NSLocalizedDescriptionKey: "DOWNLOAD_TOO_LARGE: \(fileSize) bytes > 512MB"
                    ])
                }

                let fileData = try Data(contentsOf: tempZip)
                let digest = SHA256.hash(data: fileData).map { String(format: "%02x", $0) }.joined()
                guard digest.caseInsensitiveCompare(sha256) == .orderedSame else {
                    throw NSError(domain: "LiveUpdate", code: 2, userInfo: [
                        NSLocalizedDescriptionKey: "SHA256_MISMATCH: 期望 \(sha256)，实际 \(digest)"
                    ])
                }
                if !allowUnsigned, let signature = signature,
                   let publicKey = self.defaults.string(forKey: Keys.publicKey) {
                    let ok = try self.verifySignature(data: fileData, signatureBase64: signature, publicKeyText: publicKey)
                    guard ok else {
                        throw NSError(domain: "LiveUpdate", code: 3, userInfo: [
                            NSLocalizedDescriptionKey: "SIGNATURE_INVALID: 签名校验失败（包被篡改或公钥不匹配）"
                        ])
                    }
                }

                let zip = try ZipArchive(data: fileData)
                try? FileManager.default.removeItem(at: staging)
                try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
                let extracted = try zip.extractAll(to: staging)

                let previousId = self.defaults.string(forKey: Keys.current)
                let previousVersion = self.defaults.string(forKey: Keys.currentVersion)
                try? FileManager.default.removeItem(at: target)
                try FileManager.default.moveItem(at: staging, to: target)

                // 重装同一个包（同版本 + 同 sha256）时不要把"自己"记成上一版，
                // 否则 rollback() 会"回滚到自己"，等于没有回滚目标
                let sameBundle = (previousId == bundleId)
                if sameBundle {
                    self.defaults.removeObject(forKey: Keys.previous)
                    self.defaults.removeObject(forKey: Keys.previousVersion)
                } else {
                    self.defaults.set(previousId, forKey: Keys.previous)
                    self.defaults.set(previousVersion, forKey: Keys.previousVersion)
                }
                self.defaults.set(bundleId, forKey: Keys.current)
                self.defaults.set(version, forKey: Keys.currentVersion)
                self.defaults.set(true, forKey: Keys.pending)
                self.defaults.set(0, forKey: Keys.bootAttempts)
                self.defaults.removeObject(forKey: Keys.lastNote)
                self.pruneBundles(keep: sameBundle ? [bundleId] : [bundleId, previousId].compactMap { $0 })

                self.notifyListeners("liveUpdateStateChanged", data: self.stateObject())
                call.resolve([
                    "installed": true,
                    "version": version,
                    "bundleId": bundleId,
                    "requiresReload": true,
                    "files": extracted.files,
                    "bytes": extracted.bytes
                ])

                // 先 resolve 再切换（切换会 reload，销毁 JS 上下文）
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    self.applyBasePath(target.path, reload: true)
                }
            } catch {
                try? FileManager.default.removeItem(at: staging)
                call.reject(error.localizedDescription, "INSTALL_FAILED")
            }
            try? FileManager.default.removeItem(at: tempZip)
        }
    }

    // MARK: - 状态

    @objc public func getState(_ call: CAPPluginCall) {
        call.resolve(stateObject())
    }

    private func stateObject() -> [String: Any] {
        let currentId = defaults.string(forKey: Keys.current)
        let usingOta = currentId.map { FileManager.default.fileExists(atPath: bundleDir($0).path) } ?? false
        let previousId = defaults.string(forKey: Keys.previous)
        var result: [String: Any] = [
            "platform": "ios",
            "currentVersion": usingOta
                ? (defaults.string(forKey: Keys.currentVersion) ?? currentId ?? builtinVersion)
                : builtinVersion,
            "currentBundleId": usingOta ? (currentId ?? "builtin") : "builtin",
            "builtinVersion": builtinVersion,
            "usingOta": usingOta,
            "pending": defaults.bool(forKey: Keys.pending),
            "bootAttempts": defaults.integer(forKey: Keys.bootAttempts)
        ]
        if usingOta, let previousVersion = defaults.string(forKey: Keys.previousVersion) {
            result["previousVersion"] = previousVersion
        } else {
            result["previousVersion"] = NSNull()
        }
        result["lastCheckAt"] = defaults.object(forKey: Keys.lastCheck) ?? NSNull()
        if let note = defaults.string(forKey: Keys.lastNote) {
            result["note"] = note
        }

        var updates: [[String: Any]] = []
        if let contents = try? FileManager.default.contentsOfDirectory(
            at: snapshotsDir, includingPropertiesForKeys: [.fileSizeKey]) {
            for dir in contents where dir.hasDirectoryPath && !dir.lastPathComponent.hasPrefix(".") {
                let id = dir.lastPathComponent
                // 只列本插件管理的包：同一个目录可能还有 Ionic Appflow / Capgo 的包，
                // 不能把它们当成"我们的更新包"展示或参与回滚
                guard LiveUpdatePlugin.isOwnBundleId(id) else { continue }
                var bytes = 0
                if let enumerator = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: [.fileSizeKey]) {
                    for case let fileURL as URL in enumerator {
                        bytes += (try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                    }
                }
                let version: String
                if id == currentId {
                    version = defaults.string(forKey: Keys.currentVersion) ?? id
                } else if id == previousId {
                    version = defaults.string(forKey: Keys.previousVersion) ?? id
                } else {
                    version = id
                }
                updates.append([
                    "version": version,
                    "bundleId": id,
                    "path": dir.path,
                    "bytes": bytes,
                    "active": usingOta && id == currentId,
                    "previous": id == previousId
                ])
            }
        }
        result["updates"] = updates
        return result
    }

    @objc public func notifyAppReady(_ call: CAPPluginCall) {
        let wasPending = defaults.bool(forKey: Keys.pending)
        defaults.set(false, forKey: Keys.pending)
        defaults.set(0, forKey: Keys.bootAttempts)
        call.resolve([
            "ok": true,
            "currentVersion": currentVersion,
            "pendingCleared": wasPending
        ])
    }

    @objc public func rollback(_ call: CAPPluginCall) {
        if let previous = defaults.string(forKey: Keys.previous) {
            let dir = bundleDir(previous)
            if FileManager.default.fileExists(atPath: dir.path) {
                let version = defaults.string(forKey: Keys.previousVersion) ?? previous
                defaults.set(previous, forKey: Keys.current)
                defaults.set(version, forKey: Keys.currentVersion)
                defaults.removeObject(forKey: Keys.previous)
                defaults.removeObject(forKey: Keys.previousVersion)
                defaults.set(false, forKey: Keys.pending)
                defaults.set(0, forKey: Keys.bootAttempts)
                call.resolve(["rolledBack": true, "toVersion": version, "requiresReload": true])
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    self.applyBasePath(dir.path, reload: true)
                }
                return
            }
        }
        clearState()
        call.resolve(["rolledBack": false, "toVersion": builtinVersion, "requiresReload": true])
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            self.applyBasePath(self.builtinPath, reload: true)
        }
    }

    @objc public func reset(_ call: CAPPluginCall) {
        let cleared = clearBundles()
        clearState()
        defaults.removeObject(forKey: Keys.lastNote)
        call.resolve(["cleared": cleared, "currentVersion": builtinVersion])
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            self.applyBasePath(self.builtinPath, reload: true)
        }
    }

    @objc public func reload(_ call: CAPPluginCall) {
        call.resolve()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            self.bridge?.webView?.reload()
        }
    }

    // MARK: - 离线自检

    @objc public func verifyBundle(_ call: CAPPluginCall) {
        // base64 解码 + 解压可能很大，挪出串行桥队列
        DispatchQueue.global(qos: .userInitiated).async { self.doVerifyBundle(call) }
    }

    private func doVerifyBundle(_ call: CAPPluginCall) {
        guard let zipBase64 = call.getString("zipBase64"),
              let zipData = Data(base64Encoded: zipBase64, options: .ignoreUnknownCharacters) else {
            call.reject("zipBase64 不能为空或不是合法 base64", "INVALID_ARGUMENT")
            return
        }
        let expectedSha = call.getString("sha256")
        let signature = call.getString("signature")
        let publicKey = call.getString("publicKey") ?? defaults.string(forKey: Keys.publicKey)

        let digest = SHA256.hash(data: zipData).map { String(format: "%02x", $0) }.joined()
        let shaMatches = expectedSha == nil || expectedSha!.caseInsensitiveCompare(digest) == .orderedSame

        var signatureValid: Bool?
        var note: String?
        if let signature = signature, let publicKey = publicKey, !signature.isEmpty, !publicKey.isEmpty {
            signatureValid = (try? verifySignature(
                data: zipData, signatureBase64: signature, publicKeyText: publicKey
            )) ?? false
        } else if !defaults.bool(forKey: Keys.allowUnsigned) {
            note = "没有提供 signature/publicKey，跳过验签"
        }

        var files = 0
        var bytes = 0
        var entries: [String] = []
        if shaMatches {
            let target = FileManager.default.temporaryDirectory
                .appendingPathComponent("lu-verify-\(UUID().uuidString)")
            do {
                try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
                let archive = try ZipArchive(data: zipData)
                let extracted = try archive.extractAll(to: target)
                files = extracted.files
                bytes = extracted.bytes
                entries = listFiles(in: target, prefix: "", limit: 100)
            } catch {
                note = ((note.map { $0 + "；" }) ?? "") + error.localizedDescription
            }
            try? FileManager.default.removeItem(at: target)
        }

        var result: [String: Any] = [
            "valid": shaMatches && signatureValid != false && files > 0,
            "sha256": digest,
            "files": files,
            "bytes": bytes,
            "entries": entries
        ]
        result["sha256Matches"] = expectedSha == nil ? NSNull() : shaMatches
        if let signatureValid = signatureValid {
            result["signatureValid"] = signatureValid
        } else {
            result["signatureValid"] = NSNull()
        }
        if let note = note {
            result["note"] = note
        }
        call.resolve(result)
    }

    private func listFiles(in directory: URL, prefix: String, limit: Int) -> [String] {
        var result: [String] = []
        guard let contents = try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.isDirectoryKey]
        ) else { return result }
        for url in contents.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            if result.count >= limit { break }
            let name = prefix.isEmpty ? url.lastPathComponent : prefix + "/" + url.lastPathComponent
            let isDirectory = (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            if isDirectory {
                result.append(contentsOf: listFiles(in: url, prefix: name, limit: limit - result.count))
            } else {
                result.append(name)
            }
        }
        return result
    }

    // MARK: - 根路径切换

    private func applyBasePath(_ path: String, reload: Bool) {
        if path == builtinPath {
            KeyValueStore.standard["serverBasePath"] = nil as String?
        } else {
            KeyValueStore.standard["serverBasePath"] = path
        }
        guard reload, let viewController = bridge?.viewController as? CAPBridgeViewController else {
            return
        }
        viewController.setServerBasePath(path: path)
    }

    /// 当前生效的版本号（OTA 包或内置）。用计算属性而不是方法，方便直接当值用。
    private var currentVersion: String {
        if let currentId = defaults.string(forKey: Keys.current),
           FileManager.default.fileExists(atPath: bundleDir(currentId).path) {
            return defaults.string(forKey: Keys.currentVersion) ?? currentId
        }
        return builtinVersion
    }

    // MARK: - 杂项

    private func clearState() {
        for key in [Keys.current, Keys.currentVersion, Keys.previous, Keys.previousVersion] {
            defaults.removeObject(forKey: key)
        }
        defaults.set(false, forKey: Keys.pending)
        defaults.set(0, forKey: Keys.bootAttempts)
    }

    /// 只处理本插件创建的 bundle 目录（`<version>-<sha256 前 8 位>`）。
    ///
    /// `Library/NoCloud/ionic_built_snapshots` 是 Ionic 的公共约定目录，
    /// Ionic Appflow Live Updates / Capgo（`@capgo/capacitor-updater`）也把包放在这里。
    /// 如果 `clear()` / `reset()` 无差别删除所有子目录，就会**把别的 OTA 方案的包一起删掉**，
    /// 导致那个插件下次启动找不到资源。所以这里用命名规则把"自己的包"识别出来。
    private static func isOwnBundleId(_ name: String) -> Bool {
        guard let dash = name.lastIndex(of: "-") else { return false }
        let versionPart = name[..<dash]
        let hashPart = name[name.index(after: dash)...]
        guard !versionPart.isEmpty, hashPart.count == 8 else { return false }
        guard hashPart.allSatisfy({ $0.isHexDigit }) else { return false }
        // 版本号里允许字母/数字/`.`/`_`/`-`（sanitize() 会把其它字符替换成 `_`）
        return versionPart.allSatisfy { $0.isLetter || $0.isNumber || $0 == "." || $0 == "_" || $0 == "-" }
    }

    @discardableResult
    private func clearBundles() -> Int {
        guard let contents = try? FileManager.default.contentsOfDirectory(
            at: snapshotsDir, includingPropertiesForKeys: nil) else { return 0 }
        var count = 0
        for dir in contents where dir.hasDirectoryPath {
            guard LiveUpdatePlugin.isOwnBundleId(dir.lastPathComponent) else { continue }
            try? FileManager.default.removeItem(at: dir)
            count += 1
        }
        return count
    }

    private func pruneBundles(keep: [String]) {
        guard let contents = try? FileManager.default.contentsOfDirectory(
            at: snapshotsDir, includingPropertiesForKeys: nil) else { return }
        for dir in contents where dir.hasDirectoryPath {
            let name = dir.lastPathComponent
            guard LiveUpdatePlugin.isOwnBundleId(name) else { continue }
            if !keep.contains(name) && !name.hasPrefix(".staging-") {
                try? FileManager.default.removeItem(at: dir)
            }
        }
    }

    private func sanitize(_ version: String) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-")
        var result = ""
        for scalar in version.unicodeScalars {
            result.append(allowed.contains(scalar) ? Character(scalar) : "_")
        }
        return result
    }

    private func fetchSync(url: URL) throws -> (Data, URLResponse) {
        var request = URLRequest(url: url)
        request.timeoutInterval = TimeInterval(timeoutMs) / 1000.0
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let semaphore = DispatchSemaphore(value: 0)
        var resultData: Data?
        var resultResponse: URLResponse?
        var resultError: Error?
        URLSession.shared.dataTask(with: request) { data, response, error in
            resultData = data
            resultResponse = response
            resultError = error
            semaphore.signal()
        }.resume()
        _ = semaphore.wait(timeout: .now() + TimeInterval(timeoutMs) / 1000.0 + 5)
        if let error = resultError { throw error }
        guard let data = resultData, let response = resultResponse else {
            throw NSError(domain: "LiveUpdate", code: 4,
                          userInfo: [NSLocalizedDescriptionKey: "网络请求超时"])
        }
        return (data, response)
    }

    private func verifySignature(data: Data, signatureBase64: String, publicKeyText: String) throws -> Bool {
        guard let signatureData = Data(base64Encoded: signatureBase64, options: .ignoreUnknownCharacters) else {
            throw NSError(domain: "LiveUpdate", code: 5,
                          userInfo: [NSLocalizedDescriptionKey: "SIGNATURE_INVALID: 签名不是合法 base64"])
        }
        let key: P256.Signing.PublicKey
        if publicKeyText.contains("BEGIN PUBLIC KEY") || publicKeyText.contains("BEGIN EC PUBLIC KEY") {
            key = try P256.Signing.PublicKey(pemRepresentation: publicKeyText)
        } else {
            let compact = publicKeyText.components(separatedBy: .whitespacesAndNewlines).joined()
            guard let der = Data(base64Encoded: compact, options: .ignoreUnknownCharacters) else {
                throw NSError(domain: "LiveUpdate", code: 6,
                              userInfo: [NSLocalizedDescriptionKey: "PUBLIC_KEY_INVALID: 公钥不是合法 base64"])
            }
            key = try P256.Signing.PublicKey(derRepresentation: der)
        }
        let signature = try P256.Signing.ECDSASignature(derRepresentation: signatureData)
        return key.isValidSignature(signature, for: data)
    }

    /// 语义化版本比较：数字段按数值比、字母段按字典序，
    /// 段数不同时：多出的数字段更新（1.0.0 < 1.0.0.1），多出的字母段是预发布（1.0.0 > 1.0.0-beta）。
    /// 与 Android 侧 `LiveUpdatePlugin.compareVersions` 保持完全一致的规则。
    private func compareVersions(_ left: String, _ right: String) -> Int {
        let a = left.components(separatedBy: CharacterSet(charactersIn: ".-+"))
        let b = right.components(separatedBy: CharacterSet(charactersIn: ".-+"))
        let length = max(a.count, b.count)
        for index in 0..<length {
            var partA = index < a.count ? a[index] : ""
            var partB = index < b.count ? b[index] : ""
            var numberA = Int(partA)
            var numberB = Int(partB)
            // 缺段按 0 补齐：`1.2` == `1.2.0`、`1.0.0` < `1.0.0.1`
            if partA.isEmpty, numberB != nil {
                partA = "0"
                numberA = 0
            }
            if partB.isEmpty, numberA != nil {
                partB = "0"
                numberB = 0
            }
            if partA == partB { continue }
            if let nA = numberA, let nB = numberB {
                if nA != nB { return nA < nB ? -1 : 1 }
            } else if partA.isEmpty || partB.isEmpty {
                // 缺段 vs 字母段：缺段是正式版，更新（1.0.0 > 1.0.0-beta）
                return partA.isEmpty ? 1 : -1
            } else if (numberA != nil) != (numberB != nil) {
                // 同一位置：数字段比字母段"新"
                return numberA != nil ? 1 : -1
            } else {
                let result = partA.compare(partB)
                if result != .orderedSame { return result == .orderedAscending ? -1 : 1 }
            }
        }
        return 0
    }
}

// MARK: - 下载器（带进度）

final class FileDownloader: NSObject, URLSessionDownloadDelegate {
    private let onProgress: (Int64, Int64) -> Void
    private let semaphore = DispatchSemaphore(value: 0)
    private var destination: URL?
    private var failure: Error?
    private var session: URLSession?

    init(onProgress: @escaping (Int64, Int64) -> Void) {
        self.onProgress = onProgress
        super.init()
    }

    func download(url: URL, to destination: URL, timeout: TimeInterval) throws {
        self.destination = destination
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = max(timeout, 120)
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        self.session = session
        session.downloadTask(with: url).resume()
        _ = semaphore.wait(timeout: .now() + max(timeout, 120) + 10)
        session.invalidateAndCancel()
        if let failure = failure { throw failure }
        if !FileManager.default.fileExists(atPath: destination.path) {
            throw NSError(domain: "LiveUpdate", code: 7,
                          userInfo: [NSLocalizedDescriptionKey: "下载失败：文件没有落盘"])
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                    totalBytesExpectedToWrite: Int64) {
        onProgress(totalBytesWritten, totalBytesExpectedToWrite)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didFinishDownloadingTo location: URL) {
        guard let destination = destination else { return }
        do {
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: location, to: destination)
        } catch {
            failure = error
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error { failure = error }
        semaphore.signal()
    }
}
