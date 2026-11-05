import Foundation
import UIKit
import UniformTypeIdentifiers
import Capacitor

/**
 * NativeFiles 插件（iOS 侧，jsName = "NativeFiles"）。
 *
 * 基于 **UIDocumentPickerViewController** 与 **security-scoped URL**：
 *  - `pickFiles`：系统文件选择器（支持多选，`asCopy: false` 保留原地访问 + security scope）；
 *  - `pickFolders`：文件夹选择器（`UTType.folder`，宏；
 *  - 读写/删除/列目录/新建/重命名 通过 `FileManager` / `FileHandle` 完成，不需要额外权限。
 *
 * URI 约定：iOS 返回 `file://...`，后续所有 API 的 `uri` 参数都用它。
 * 注意：App 重启后，选择器授予的 security-scoped 访问会失效；返回的 `bookmark` 可用于重新访问
 * （当前版本只返回，尚未提供按 bookmark 恢复的 API）。
 */
@objc(NativeFilesPlugin)
public class NativeFilesPlugin: CAPPlugin, CAPBridgedPlugin {
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
        CAPPluginMethod(name: "copyToAppStorage", returnType: CAPPluginReturnPromise)
    ]

    private static let maxReadBytes: Int64 = 16 * 1024 * 1024
    private var pendingPickCall: CAPPluginCall?
    private var pendingPickIsFolder = false

    // ---------------------------------------------------------------- 选择器

    @objc func pickFiles(_ call: CAPPluginCall) {
        let multiple = call.getBool("multiple", false)
        let mimeTypes = call.getArray("mimeTypes", String.self) ?? []
        DispatchQueue.main.async {
            var types: [UTType] = []
            for mime in mimeTypes {
                if let type = UTType(mime) {
                    types.append(type)
                }
            }
            if types.isEmpty {
                types = [.item]
            }
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: false)
            picker.allowsMultipleSelection = multiple
            picker.delegate = self
            self.pendingPickCall = call
            self.pendingPickIsFolder = false
            self.bridge?.viewController?.present(picker, animated: true)
        }
    }

    @objc func pickFolders(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder], asCopy: false)
            // 文件夹选择保持单选（与 Android SAF 行为一致）
            picker.allowsMultipleSelection = false
            picker.delegate = self
            self.pendingPickCall = call
            self.pendingPickIsFolder = true
            self.bridge?.viewController?.present(picker, animated: true)
        }
    }

    // ---------------------------------------------------------------- 文件读写

    @objc func readFile(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri"), let url = URL(string: uri) else {
            call.reject("missing or invalid uri")
            return
        }
        let offset = call.getInt("offset", 0)
        let requested = call.getDouble("length", 0)
        let size = fileSize(url)
        var length: Int64 = size
        if requested > 0 {
            length = min(Int64(requested), Self.maxReadBytes)
        } else {
            length = min(size, Self.maxReadBytes)
        }
        do {
            let data = try readData(url: url, offset: Int64(max(0, offset)), length: length)
            call.resolve([
                "base64": data.base64EncodedString(),
                "bytes": data.count,
                "size": size
            ])
        } catch {
            call.reject("read failed: \(error.localizedDescription)")
        }
    }

    @objc func writeFile(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri"), let url = URL(string: uri) else {
            call.reject("missing or invalid uri")
            return
        }
        guard let base64 = call.getString("base64"), let data = Data(base64Encoded: base64) else {
            call.reject("missing or invalid base64")
            return
        }
        let mode = call.getString("mode", "overwrite") ?? "overwrite"

        do {
            if mode == "create", FileManager.default.fileExists(atPath: url.path) {
                call.reject("FILE_ALREADY_EXISTS")
                return
            }
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            try withFileHandle(url: url, mode: mode) { handle in
                if mode == "append" {
                    handle.seekToEndOfFile()
                } else {
                    handle.truncateFile(atOffset: 0)
                }
                handle.write(data)
                if #available(iOS 13.0, *) {
                    try handle.synchronize()
                }
            }
            call.resolve([
                "bytes": data.count,
                "size": fileSize(url)
            ])
        } catch {
            call.reject("write failed: \(error.localizedDescription)")
        }
    }

    @objc func deleteFile(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri"), let url = URL(string: uri) else {
            call.reject("missing or invalid uri")
            return
        }
        do {
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            try FileManager.default.removeItem(at: url)
            call.resolve()
        } catch {
            call.reject("delete failed: \(error.localizedDescription)")
        }
    }

    // ---------------------------------------------------------------- 文件夹

    @objc func listFolder(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri"), let url = URL(string: uri) else {
            call.reject("missing or invalid uri")
            return
        }
        do {
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            let keys: [URLResourceKey] = [
                .isDirectoryKey, .fileSizeKey, .contentTypeKey, .contentModificationDateKey
            ]
            let contents = try FileManager.default.contentsOfDirectory(
                at: url,
                includingPropertiesForKeys: keys,
                options: []
            )
            let sorted = contents.sorted { left, right in
                let leftDir = (try? left.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
                let rightDir = (try? right.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
                if leftDir != rightDir {
                    return leftDir
                }
                return left.lastPathComponent.localizedStandardCompare(right.lastPathComponent) == .orderedAscending
            }
            let entries: [[String: Any]] = sorted.map { entry in
                let values = try? entry.resourceValues(forKeys: Set(keys))
                return [
                    "uri": entry.absoluteString,
                    "name": entry.lastPathComponent,
                    "isDirectory": values?.isDirectory ?? false,
                    "size": values?.fileSize ?? 0,
                    "mimeType": values?.contentType?.preferredMIMEType ?? "",
                    "lastModified": Int64((values?.contentModificationDate?.timeIntervalSince1970 ?? 0) * 1000)
                ]
            }
            call.resolve(["entries": entries])
        } catch {
            call.reject("list failed: \(error.localizedDescription)")
        }
    }

    @objc func createFolder(_ call: CAPPluginCall) {
        guard
            let parentUri = call.getString("parentUri"),
            let parentURL = URL(string: parentUri),
            let name = call.getString("name"),
            !name.isEmpty
        else {
            call.reject("missing parentUri or name")
            return
        }
        do {
            let scoped = parentURL.startAccessingSecurityScopedResource()
            defer {
                if scoped {
                    parentURL.stopAccessingSecurityScopedResource()
                }
            }
            let target = parentURL.appendingPathComponent(name, isDirectory: true)
            try FileManager.default.createDirectory(at: target, withIntermediateDirectories: false)
            call.resolve(entryDict(for: target))
        } catch {
            call.reject("create folder failed: \(error.localizedDescription)")
        }
    }

    @objc func deleteFolder(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri"), let url = URL(string: uri) else {
            call.reject("missing or invalid uri")
            return
        }
        do {
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            try FileManager.default.removeItem(at: url)
            call.resolve()
        } catch {
            call.reject("delete folder failed: \(error.localizedDescription)")
        }
    }

    @objc func renameFolder(_ call: CAPPluginCall) {
        guard
            let uri = call.getString("uri"),
            let url = URL(string: uri),
            let newName = call.getString("newName"),
            !newName.isEmpty
        else {
            call.reject("missing uri or newName")
            return
        }
        do {
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            let target = url.deletingLastPathComponent().appendingPathComponent(newName, isDirectory: true)
            try FileManager.default.moveItem(at: url, to: target)
            call.resolve(entryDict(for: target))
        } catch {
            call.reject("rename failed: \(error.localizedDescription)")
        }
    }

    @objc func getInfo(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri"), let url = URL(string: uri) else {
            call.reject("missing or invalid uri")
            return
        }
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped {
                url.stopAccessingSecurityScopedResource()
            }
        }
        let exists = FileManager.default.fileExists(atPath: url.path)
        var isDirectory: ObjCBool = false
        if exists {
            FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory)
        }
        let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentTypeKey, .contentModificationDateKey])
        call.resolve([
            "uri": uri,
            "name": url.lastPathComponent,
            "exists": exists,
            "isDirectory": isDirectory.boolValue,
            "size": values?.fileSize ?? 0,
            "mimeType": values?.contentType?.preferredMIMEType ?? "",
            "lastModified": Int64((values?.contentModificationDate?.timeIntervalSince1970 ?? 0) * 1000)
        ])
    }

    // ---------------------------------------------------------------- 复制到 App 存储（配合 data-channel）

    @objc func copyToAppStorage(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri"), let url = URL(string: uri) else {
            call.reject("missing or invalid uri")
            return
        }
        let relative = call.getString("relativePath", nil) ?? url.lastPathComponent
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            call.reject("cannot resolve documents directory")
            return
        }
        // 与 data-channel 的 iOS 默认根目录一致
        let root = documents.appendingPathComponent("dataChannel", isDirectory: true)
        let target = root.appendingPathComponent(relative)
        do {
            if !FileManager.default.fileExists(atPath: root.path) {
                try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            }
            let rootPath = root.standardizedFileURL.path
            let targetPath = target.standardizedFileURL.path
            if targetPath != rootPath, !targetPath.hasPrefix(rootPath + "/") {
                call.reject("invalid relativePath")
                return
            }
            let parent = target.deletingLastPathComponent()
            if !FileManager.default.fileExists(atPath: parent.path) {
                try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
            }
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.copyItem(at: url, to: target)
            let size = fileSize(target)
            call.resolve([
                "absolutePath": target.path,
                "relativePath": relative,
                "bytes": size
            ])
        } catch {
            call.reject("copy failed: \(error.localizedDescription)")
        }
    }

    // ---------------------------------------------------------------- 工具

    private func fileSize(_ url: URL) -> Int64 {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values?.fileSize ?? 0)
    }

    private func readData(url: URL, offset: Int64, length: Int64) throws -> Data {
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped {
                url.stopAccessingSecurityScopedResource()
            }
        }
        let handle = try FileHandle(forReadingFrom: url)
        defer {
            try? handle.close()
        }
        handle.seek(toFileOffset: UInt64(max(0, offset)))
        return handle.readData(ofLength: Int(max(0, length)))
    }

    private func withFileHandle(url: URL, mode: String, _ body: (FileHandle) throws -> Void) throws {
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped {
                url.stopAccessingSecurityScopedResource()
            }
        }
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        let handle = try FileHandle(forWritingTo: url)
        defer {
            try? handle.close()
        }
        try body(handle)
    }

    private func entryDict(for url: URL) -> [String: Any] {
        let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .contentTypeKey, .contentModificationDateKey])
        return [
            "uri": url.absoluteString,
            "name": url.lastPathComponent,
            "isDirectory": values?.isDirectory ?? false,
            "size": values?.fileSize ?? 0,
            "mimeType": values?.contentType?.preferredMIMEType ?? "",
            "lastModified": Int64((values?.contentModificationDate?.timeIntervalSince1970 ?? 0) * 1000)
        ]
    }
}

// MARK: - UIDocumentPickerDelegate

extension NativeFilesPlugin: UIDocumentPickerDelegate {
    public func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let call = pendingPickCall else {
            return
        }
        pendingPickCall = nil
        let isFolder = pendingPickIsFolder
        pendingPickIsFolder = false

        if urls.isEmpty {
            call.resolve(["files": [], "folders": [], "cancelled": true])
            return
        }

        var entries: [[String: Any]] = []
        for url in urls {
            let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .contentTypeKey, .contentModificationDateKey])
            var entry: [String: Any] = [
                "uri": url.absoluteString,
                "name": url.lastPathComponent,
                "isDirectory": values?.isDirectory ?? isFolder,
                "size": values?.fileSize ?? 0,
                "mimeType": values?.contentType?.preferredMIMEType ?? "",
                "lastModified": Int64((values?.contentModificationDate?.timeIntervalSince1970 ?? 0) * 1000)
            ]
            if !isFolder {
                if let bookmark = try? url.bookmarkData() {
                    entry["bookmark"] = bookmark.base64EncodedString()
                }
            }
            entries.append(entry)
        }

        if isFolder {
            call.resolve(["folders": entries, "cancelled": false])
        } else {
            call.resolve(["files": entries, "cancelled": false])
        }
    }

    public func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        guard let call = pendingPickCall else {
            return
        }
        pendingPickCall = nil
        let isFolder = pendingPickIsFolder
        pendingPickIsFolder = false
        if isFolder {
            call.resolve(["folders": [], "cancelled": true])
        } else {
            call.resolve(["files": [], "cancelled": true])
        }
    }
}
