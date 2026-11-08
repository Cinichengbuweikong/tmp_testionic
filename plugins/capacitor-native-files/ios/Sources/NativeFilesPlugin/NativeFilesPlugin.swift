import Foundation
import UIKit
import UniformTypeIdentifiers
import Capacitor

/**
 * NativeFiles 插件（iOS 侧，jsName = "NativeFiles"）。
 *
 * ============================ 一、路径语义：真实绝对路径 ============================
 *
 * 所有 API 都用**绝对路径**（如 `/var/mobile/Containers/Data/Application/<uuid>/Documents/Notes/a.md`），
 * 不用 `file://`。前端可以拼路径、算父目录/子目录。
 *
 * 用户在系统选择器里选中的、App 沙盒**之外**的文件夹/文件，iOS 只给 "security-scoped" 授权：
 *  - 选择时把 URL 转成 bookmark（iOS 的 `url.bookmarkData()`；注意 macOS 专用的
 *    `.withSecurityScope` 选项在 iOS 上不可用），按绝对路径
 *    存进 UserDefaults（持久化，App 重启后仍有效）；
 *  - 之后任何操作都用绝对路径 → 插件找到**最长的已授权祖先路径**，start/stop accessing，
 *    再对目标路径做文件操作。所以"拼出来的子路径"也能正常读写。
 *
 * 沙盒内的路径（Documents / Library / tmp）不需要授权，直接访问。
 *
 * ============================ 二、与 Android 的差异 ============================
 *
 * | 能力 | Android | iOS |
 * |------|---------|-----|
 * | 公共目录 | 有（需所有文件访问权限） | **没有**：`downloads/pictures/...` 在对应 API（如 `getDownloadsDir()`）里返回 null |
 * | 文件夹选择 | SAF（系统限制单选） | UIDocumentPicker（文件夹）单选 |
 * | 权限 API | `requestAllFilesAccess()` 打开设置页 | 无此概念 → `{ granted: true, applicable: false }` |
 *
 * ============================ 三、线程 ============================
 *
 * 与 Android 一样，插件方法不在主线程；文件操作在后台队列，UI（present picker）切主线程。
 */
@objc(NativeFilesPlugin)
public class NativeFilesPlugin: CAPPlugin, CAPBridgedPlugin, UIDocumentPickerDelegate {
    public let identifier = "NativeFilesPlugin"
    public let jsName = "NativeFiles"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "pickFiles", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pickFolders", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "readFile", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "writeFile", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deleteFile", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "listFolder", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "createFolder", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "deleteFolder", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "renameFolder", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkAllFilesAccess", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestAllFilesAccess", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAppFilesDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAppCacheDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAppTempDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAppExternalFilesDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAppExternalCacheDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAppDocumentsDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSharedStorageDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDownloadsDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSharedDocumentsDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPicturesDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDcimDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getMoviesDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getMusicDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAlarmsDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getNotificationsDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPodcastsDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getRingtonesDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAppDataDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAppExternalRootDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getNativeLibDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLibraryDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getIcloudDocumentsDir", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAppInstallPath", returnType: CAPPluginReturnPromise),
    ]

    private static let readLimit: Int64 = 16 * 1024 * 1024
    private static let bookmarksKey = "NativeFiles.securityScopedBookmarks.v2"

    private enum PickKind {
        case files
        case folders
    }

    private var pendingPick: CAPPluginCall?
    private var pendingKind: PickKind = .files
    private var bookmarks: [String: Data] = [:]

    // ================================================================ 生命周期

    override public func load() {
        super.load()
        bookmarks = Self.loadBookmarks()
    }

    // ================================================================ 选择器

    @objc func pickFiles(_ call: CAPPluginCall) {
        presentPicker(kind: .files, call: call)
    }

    @objc func pickFolders(_ call: CAPPluginCall) {
        presentPicker(kind: .folders, call: call)
    }

    private func presentPicker(kind: PickKind, call: CAPPluginCall) {
        DispatchQueue.main.async {
            if self.pendingPick != nil {
                call.reject("PICKER_BUSY: another picker is already open")
                return
            }
            guard let host = self.bridge?.viewController else {
                call.reject("BRIDGE_UNAVAILABLE")
                return
            }
            let types: [UTType] = kind == .folders ? [.folder] : [.item]
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: false)
            if kind == .files {
                picker.allowsMultipleSelection = call.getBool("multiple", false)
            } else {
                picker.allowsMultipleSelection = false // Android SAF 只能单选，保持跨平台一致
            }
            picker.delegate = self
            self.pendingPick = call
            self.pendingKind = kind
            host.present(picker, animated: true)
        }
    }

    public func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let call = pendingPick else {
            return
        }
        let kind = pendingKind
        pendingPick = nil

        if kind == .folders {
            guard let url = urls.first else {
                call.resolve(["folders": [], "cancelled": true])
                return
            }
            saveBookmark(for: url)
            do {
                let entry = try withScopedAccess(url.path) { target in try dirEntry(target) }
                call.resolve(["folders": [entry], "cancelled": false])
            } catch {
                call.reject(describe(error, action: "read \(url.path)"))
            }
            return
        }

        var files: [[String: Any]] = []
        var failures: [String] = []
        for url in urls {
            saveBookmark(for: url)
            do {
                let entry = try withScopedAccess(url.path) { target in try fileEntry(target) }
                files.append(entry)
            } catch {
                failures.append("\(url.path): \(error.localizedDescription)")
            }
        }
        var result: [String: Any] = ["files": files, "cancelled": false]
        if !failures.isEmpty {
            result["warning"] = failures.joined(separator: "; ")
        }
        call.resolve(result)
    }

    public func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        guard let call = pendingPick else {
            return
        }
        let kind = pendingKind
        pendingPick = nil
        if kind == .folders {
            call.resolve(["folders": [], "cancelled": true])
        } else {
            call.resolve(["files": [], "cancelled": true])
        }
    }

    // ================================================================ 文件读写

    @objc func readFile(_ call: CAPPluginCall) {
        guard let path = requiredPath(call) else {
            return
        }
        let offset = max(0, call.getInt("offset", 0))
        let requested = call.getInt("length", Int(Self.readLimit))
        let length = max(1, min(Int(Self.readLimit), requested))

        do {
            let payload = try withScopedAccess(path) { url -> [String: Any] in
                guard FileManager.default.fileExists(atPath: url.path) else {
                    throw FsError("NOT_FOUND: \(url.path)")
                }
                let handle = try FileHandle(forReadingFrom: url)
                defer { try? handle.close() }
                try handle.seek(toOffset: UInt64(offset))
                let maybeData = try handle.read(upToCount: length)
                let data = maybeData ?? Data()
                let attrs = (try? FileManager.default.attributesOfItem(atPath: url.path)) ?? [:]
                let size = (attrs[.size] as? NSNumber)?.int64Value ?? Int64(data.count)
                return [
                    "path": url.path,
                    "base64": data.base64EncodedString(),
                    "bytes": data.count,
                    "size": size,
                    "offset": offset
                ]
            }
            call.resolve(payload)
        } catch {
            call.reject(describe(error, action: "read \(path)"))
        }
    }

    @objc func writeFile(_ call: CAPPluginCall) {
        guard let path = requiredPath(call) else {
            return
        }
        guard let base64 = call.getString("base64"), let data = Data(base64Encoded: base64) else {
            call.reject("missing base64 or invalid base64")
            return
        }
        let mode = call.getString("mode", "overwrite")

        do {
            let payload = try withScopedAccess(path) { url -> [String: Any] in
                let fm = FileManager.default
                if mode == "create" && fm.fileExists(atPath: url.path) {
                    throw FsError("FILE_ALREADY_EXISTS: \(url.path)")
                }
                let parent = url.deletingLastPathComponent()
                if !fm.fileExists(atPath: parent.path) {
                    try fm.createDirectory(at: parent, withIntermediateDirectories: true)
                }
                if !fm.fileExists(atPath: url.path) {
                    fm.createFile(atPath: url.path, contents: nil)
                }
                let handle = try FileHandle(forWritingTo: url)
                defer { try? handle.close() }
                if mode == "append" {
                    _ = try handle.seekToEnd()
                } else {
                    try handle.truncate(atOffset: 0)
                }
                try handle.write(contentsOf: data)
                let attrs = (try? fm.attributesOfItem(atPath: url.path)) ?? [:]
                let size = (attrs[.size] as? NSNumber)?.int64Value ?? Int64(data.count)
                return ["path": url.path, "bytes": data.count, "size": size]
            }
            call.resolve(payload)
        } catch {
            call.reject(describe(error, action: "write \(path)"))
        }
    }

    @objc func deleteFile(_ call: CAPPluginCall) {
        guard let path = requiredPath(call) else {
            return
        }
        do {
            let payload = try withScopedAccess(path) { url -> [String: Any] in
                let fm = FileManager.default
                guard fm.fileExists(atPath: url.path) else {
                    return ["deleted": false, "reason": "NOT_FOUND"]
                }
                var isDirectory: ObjCBool = false
                fm.fileExists(atPath: url.path, isDirectory: &isDirectory)
                if isDirectory.boolValue {
                    throw FsError("IS_A_DIRECTORY: use deleteFolder() for directories")
                }
                try fm.removeItem(at: url)
                return ["deleted": true]
            }
            call.resolve(payload)
        } catch {
            call.reject(describe(error, action: "delete \(path)"))
        }
    }

    // ================================================================ 文件夹

    @objc func listFolder(_ call: CAPPluginCall) {
        guard let path = requiredPath(call) else {
            return
        }
        let recursive = call.getBool("recursive", false)
        let maxEntries = max(1, min(20000, call.getInt("maxEntries", 5000)))

        do {
            let payload = try withScopedAccess(path) { url -> [String: Any] in
                let fm = FileManager.default
                var isDirectory: ObjCBool = false
                guard fm.fileExists(atPath: url.path, isDirectory: &isDirectory), isDirectory.boolValue else {
                    throw FsError("NOT_A_DIRECTORY: \(url.path)")
                }
                var entries: [[String: Any]] = []
                try collectEntries(url, recursive: recursive, maxEntries: maxEntries, into: &entries)
                return [
                    "path": url.path,
                    "entries": entries,
                    "truncated": entries.count >= maxEntries
                ]
            }
            call.resolve(payload)
        } catch {
            call.reject(describe(error, action: "list \(path)"))
        }
    }

    private func collectEntries(
        _ dir: URL,
        recursive: Bool,
        maxEntries: Int,
        into out: inout [[String: Any]]
    ) throws {
        let fm = FileManager.default
        let children = try fm.contentsOfDirectory(
            at: dir,
            includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        )
        let sorted = children.sorted { a, b in
            let ad = (try? a.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            let bd = (try? b.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            if ad != bd {
                return ad && !bd
            }
            return a.lastPathComponent.lowercased() < b.lastPathComponent.lowercased()
        }
        for child in sorted {
            if out.count >= maxEntries {
                return
            }
            let isDir = (try? child.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            if isDir {
                out.append(try dirEntry(child))
            } else {
                out.append(try fileEntry(child))
            }
            if recursive && isDir {
                try collectEntries(child, recursive: true, maxEntries: maxEntries, into: &out)
            }
        }
    }

    @objc func createFolder(_ call: CAPPluginCall) {
        guard let path = requiredPath(call) else {
            return
        }
        do {
            let payload = try withScopedAccess(path) { url -> [String: Any] in
                let fm = FileManager.default
                if fm.fileExists(atPath: url.path) {
                    var isDirectory: ObjCBool = false
                    fm.fileExists(atPath: url.path, isDirectory: &isDirectory)
                    if isDirectory.boolValue {
                        return ["path": url.path, "created": false]
                    }
                    throw FsError("PATH_EXISTS_AS_FILE: \(url.path)")
                }
                try fm.createDirectory(at: url, withIntermediateDirectories: true)
                return ["path": url.path, "created": true]
            }
            call.resolve(payload)
        } catch {
            call.reject(describe(error, action: "create folder \(path)"))
        }
    }

    @objc func deleteFolder(_ call: CAPPluginCall) {
        guard let path = requiredPath(call) else {
            return
        }
        let recursive = call.getBool("recursive", false)
        do {
            let payload = try withScopedAccess(path) { url -> [String: Any] in
                let fm = FileManager.default
                var isDirectory: ObjCBool = false
                guard fm.fileExists(atPath: url.path, isDirectory: &isDirectory), isDirectory.boolValue else {
                    throw FsError("NOT_A_DIRECTORY: \(url.path)")
                }
                if !recursive {
                    let children = try fm.contentsOfDirectory(atPath: url.path)
                    if !children.isEmpty {
                        return [
                            "deleted": false,
                            "reason": "NOT_EMPTY_OR_FAILED",
                            "hint": "pass recursive: true to delete non-empty directories"
                        ]
                    }
                }
                try fm.removeItem(at: url)
                return ["deleted": true]
            }
            call.resolve(payload)
        } catch {
            call.reject(describe(error, action: "delete folder \(path)"))
        }
    }

    @objc func renameFolder(_ call: CAPPluginCall) {
        guard let path = requiredPath(call) else {
            return
        }
        guard let newName = call.getString("newName"), !newName.isEmpty else {
            call.reject("missing newName")
            return
        }
        if newName.contains("/") || newName == "." || newName == ".." {
            call.reject("INVALID_NAME: newName must be a simple file/folder name")
            return
        }
        do {
            let payload = try withScopedAccess(path) { url -> [String: Any] in
                let fm = FileManager.default
                guard fm.fileExists(atPath: url.path) else {
                    throw FsError("NOT_FOUND: \(url.path)")
                }
                let target = url.deletingLastPathComponent().appendingPathComponent(newName)
                if fm.fileExists(atPath: target.path) {
                    throw FsError("TARGET_EXISTS: \(target.path)")
                }
                try fm.moveItem(at: url, to: target)
                return ["renamed": true, "path": target.path, "from": url.path]
            }
            call.resolve(payload)
        } catch {
            call.reject(describe(error, action: "rename \(path)"))
        }
    }

    // ================================================================ 信息

    @objc func getInfo(_ call: CAPPluginCall) {
        guard let path = requiredPath(call) else {
            return
        }
        do {
            let payload = try withScopedAccess(path) { url -> [String: Any] in
                let fm = FileManager.default
                let exists = fm.fileExists(atPath: url.path)
                var isDirectory: ObjCBool = false
                if exists {
                    fm.fileExists(atPath: url.path, isDirectory: &isDirectory)
                }
                let attrs = (try? fm.attributesOfItem(atPath: url.path)) ?? [:]
                let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
                let modified = (attrs[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
                return [
                    "path": url.path,
                    "name": url.lastPathComponent,
                    "exists": exists,
                    "isDirectory": isDirectory.boolValue,
                    "isFile": exists && !isDirectory.boolValue,
                    "size": isDirectory.boolValue ? 0 : size,
                    "lastModified": Int64(modified * 1000),
                    "readable": fm.isReadableFile(atPath: url.path),
                    "writable": fm.isWritableFile(atPath: url.path),
                    "mimeType": isDirectory.boolValue ? "inode/directory" : Self.mimeType(for: url.lastPathComponent)
                ]
            }
            call.resolve(payload)
        } catch {
            call.reject(describe(error, action: "getInfo \(path)"))
        }
    }

    // ================================================================ 权限（iOS 无此概念）

    @objc func checkAllFilesAccess(_ call: CAPPluginCall) {
        call.resolve(noAllFilesAccessPayload())
    }

    @objc func requestAllFilesAccess(_ call: CAPPluginCall) {
        call.resolve(noAllFilesAccessPayload())
    }

    private func noAllFilesAccessPayload() -> [String: Any] {
        [
            "granted": true,
            "applicable": false,
            "message": "iOS has no \"all files access\" permission: use the document picker to select a folder once; this plugin stores a security-scoped bookmark so the absolute path keeps working (including child paths)."
        ]
    }

    // ================================================================ 单条路径查询
    //
    // 与 Android 保持同一组方法：**一个方法只查一条路径**，返回 `{ path, reason? }`；
    // iOS 没有"公共目录"概念，对应的几个方法返回 `path: null` + reason。

    private func resolvePath(_ call: CAPPluginCall, _ url: URL?, reasonIfNil: String = "NOT_AVAILABLE") {
        if let url = url {
            call.resolve(["path": url.path])
        } else {
            call.resolve(["path": NSNull(), "reason": reasonIfNil])
        }
    }

    private func sandboxURL(_ directory: FileManager.SearchPathDirectory) -> URL? {
        FileManager.default.urls(for: directory, in: .userDomainMask).first
    }

    /// App 私有"文件"目录：iOS 上是 `Library/Application Support`（与 Android 的 files 对应）。
    @objc func getAppFilesDir(_ call: CAPPluginCall) {
        resolvePath(call, sandboxURL(.applicationSupportDirectory))
    }

    /// App 私有缓存目录：`Library/Caches`（系统可能随时清理）。
    @objc func getAppCacheDir(_ call: CAPPluginCall) {
        resolvePath(call, sandboxURL(.cachesDirectory))
    }

    /// 临时目录：`tmp`（系统可能随时清理）。
    @objc func getAppTempDir(_ call: CAPPluginCall) {
        resolvePath(call, URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true))
    }

    /// App 外部私有目录：iOS 没有（沙盒内所有目录都算"内部"），返回 null。
    @objc func getAppExternalFilesDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// App 外部私有缓存目录：iOS 没有，返回 null。
    @objc func getAppExternalCacheDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// App 文档目录：`Documents`（用户数据；开启 `UIFileSharingEnabled` 后可在"文件"App 中看到）。
    @objc func getAppDocumentsDir(_ call: CAPPluginCall) {
        resolvePath(call, sandboxURL(.documentDirectory))
    }

    /// 共享存储根目录：iOS 没有公共文件系统，返回 null。
    @objc func getSharedStorageDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// 系统下载目录：iOS 没有，返回 null。
    @objc func getDownloadsDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// 系统文档目录：iOS 没有公共目录，返回 null（App 自己的用 `getAppDocumentsDir()`）。
    @objc func getSharedDocumentsDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// 系统图片目录：iOS 没有，返回 null（照片库需 PhotoKit）。
    @objc func getPicturesDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// 系统相机目录：iOS 没有，返回 null。
    @objc func getDcimDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// 系统视频目录：iOS 没有，返回 null。
    @objc func getMoviesDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// 系统音乐目录：iOS 没有，返回 null。
    @objc func getMusicDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// 系统闹钟铃声目录：iOS 没有，返回 null。
    @objc func getAlarmsDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// 系统通知铃声目录：iOS 没有，返回 null。
    @objc func getNotificationsDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// 系统播客目录：iOS 没有，返回 null。
    @objc func getPodcastsDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// 系统铃声目录：iOS 没有，返回 null。
    @objc func getRingtonesDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// App 数据根目录：iOS 上是沙盒根 `NSHomeDirectory()`。
    @objc func getAppDataDir(_ call: CAPPluginCall) {
        resolvePath(call, URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true))
    }

    /// App 外部私有根目录：iOS 没有，返回 null。
    @objc func getAppExternalRootDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// native 库目录：iOS 没有该概念（动态库在 `.app`/Frameworks 里），返回 null。
    @objc func getNativeLibDir(_ call: CAPPluginCall) {
        resolvePath(call, nil, reasonIfNil: "NOT_APPLICABLE_ON_IOS")
    }

    /// `Library` 目录：`Library`（`Application Support` / `Caches` 的上级）。
    @objc func getLibraryDir(_ call: CAPPluginCall) {
        resolvePath(call, sandboxURL(.libraryDirectory))
    }

    /// iCloud Documents 目录（需要宿主开启 iCloud 能力；未开启时返回 null）。
    @objc func getIcloudDocumentsDir(_ call: CAPPluginCall) {
        let url = FileManager.default
            .url(forUbiquityContainerIdentifier: nil)?
            .appendingPathComponent("Documents", isDirectory: true)
        resolvePath(call, url, reasonIfNil: "ICLOUD_NOT_AVAILABLE")
    }

    /// App 安装包路径：iOS 上是 `.app` 包目录。
    @objc func getAppInstallPath(_ call: CAPPluginCall) {
        resolvePath(call, Bundle.main.bundleURL)
    }

    // ================================================================ security-scoped bookmark

    private func saveBookmark(for url: URL) {
        let path = url.standardizedFileURL.path
        do {
            // 注意：`URL.BookmarkCreationOptions.withSecurityScope` 是 macOS 专用，iOS 上传这个选项
            // 会编译报错（'withSecurityScope' is unavailable in iOS）。
            // iOS 上 document picker 给的 URL 本身自带 security scope，直接 bookmarkData() 即可，
            // 解析时也不需要 .withSecurityScope。
            let data = try url.bookmarkData(
                options: [],
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
            bookmarks[path] = data
            persistBookmarks()
        } catch {
            CAPLog.print("[NativeFiles] cannot create bookmark for \(path): \(error.localizedDescription)")
        }
    }

    private func persistBookmarks() {
        UserDefaults.standard.set(bookmarks, forKey: Self.bookmarksKey)
    }

    private static func loadBookmarks() -> [String: Data] {
        guard let stored = UserDefaults.standard.dictionary(forKey: bookmarksKey) else {
            return [:]
        }
        var result: [String: Data] = [:]
        for (key, value) in stored {
            if let data = value as? Data {
                result[key] = data
            }
        }
        return result
    }

    /// 找到**最长的已授权祖先路径**（用户可能拼了子路径）。
    private func scopedAncestor(for url: URL) -> (URL, Data)? {
        let target = url.standardizedFileURL.path
        var bestPath: String?
        var bestData: Data?
        for (path, data) in bookmarks {
            if target == path || target.hasPrefix(path + "/") {
                if bestPath == nil || path.count > bestPath!.count {
                    bestPath = path
                    bestData = data
                }
            }
        }
        guard let data = bestData else {
            return nil
        }
        var stale = false
        guard let resolved = try? URL(
            resolvingBookmarkData: data,
            options: [],   // iOS 没有 .withSecurityScope（macOS 专用）
            relativeTo: nil,
            bookmarkDataIsStale: &stale
        ) else {
            return nil
        }
        if stale {
            saveBookmark(for: resolved)
        }
        return (resolved, data)
    }

    private func isInsideSandbox(_ url: URL) -> Bool {
        url.standardizedFileURL.path.hasPrefix(NSHomeDirectory())
    }

    /// 在 security-scoped 授权内执行文件操作（沙盒内直接执行）。
    private func withScopedAccess<T>(_ path: String, _ body: (URL) throws -> T) throws -> T {
        let url = URL(fileURLWithPath: path).standardizedFileURL
        if isInsideSandbox(url) {
            return try body(url)
        }
        if let (ancestor, _) = scopedAncestor(for: url) {
            let started = ancestor.startAccessingSecurityScopedResource()
            defer {
                if started {
                    ancestor.stopAccessingSecurityScopedResource()
                }
            }
            return try body(url)
        }
        return try body(url)
    }

    // ================================================================ 工具

    private func requiredPath(_ call: CAPPluginCall) -> String? {
        guard let raw = call.getString("path")?.trimmingCharacters(in: .whitespaces), !raw.isEmpty else {
            call.reject("missing path")
            return nil
        }
        var path = raw
        if path.hasPrefix("file://") {
            path = String(path.dropFirst("file://".count))
        }
        if !path.hasPrefix("/") {
            call.reject("PATH_MUST_BE_ABSOLUTE: \(raw)")
            return nil
        }
        return path
    }

    private func fileEntry(_ url: URL) throws -> [String: Any] {
        let attrs = (try? FileManager.default.attributesOfItem(atPath: url.path)) ?? [:]
        let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        let modified = (attrs[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
        return [
            "path": url.path,
            "name": url.lastPathComponent,
            "isDirectory": false,
            "size": size,
            "lastModified": Int64(modified * 1000),
            "mimeType": Self.mimeType(for: url.lastPathComponent)
        ]
    }

    private func dirEntry(_ url: URL) throws -> [String: Any] {
        let attrs = (try? FileManager.default.attributesOfItem(atPath: url.path)) ?? [:]
        let modified = (attrs[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
        return [
            "path": url.path,
            "name": url.lastPathComponent,
            "isDirectory": true,
            "size": 0,
            "lastModified": Int64(modified * 1000),
            "mimeType": "inode/directory"
        ]
    }

    private static func mimeType(for name: String) -> String {
        let lower = name.lowercased()
        if let dot = lower.lastIndex(of: "."), dot < lower.endIndex {
            let ext = String(lower[lower.index(after: dot)...])
            if let utType = UTType(filenameExtension: ext), let mime = utType.preferredMIMEType {
                return mime
            }
        }
        return ""
    }

    private func describe(_ error: Error, action: String) -> String {
        if let fsError = error as? FsError {
            return fsError.message
        }
        return "FAILED: cannot \(action) (\(error.localizedDescription))"
    }
}

/// 携带明确错误码的简单错误类型（与 Android 的 reject 文案保持一致）。
struct FsError: Error {
    let message: String

    init(_ message: String) {
        self.message = message
    }
}
