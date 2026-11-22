import Compression
import Foundation

/**
 * 一个极简的 ZIP 解压器（只依赖系统 Compression 框架，不引入任何 SPM 依赖）。
 *
 * 支持：
 * - Store（method 0）与 Deflate（method 8）；
 * - ZIP64 不支持（热更新包不会超过 4GB，遇到就报错）；
 * - 会校验 CRC32 与解压后大小；
 * - 防 zip-slip（拒绝绝对路径、`..`、盘符）。
 *
 * 为什么自己写：iOS 的 Foundation **没有**公开的解压 API，而引第三方 SPM 依赖会让
 * `cap sync` 的 Swift Package 解析更脆弱（CI 上已经遇到过 CDN 504）。ZIP 的中央目录格式
 * 很简单，自己解析 200 行就够，且可以精确控制安全校验。
 */
struct ZipEntry {
    let name: String
    let method: UInt16
    let crc32: UInt32
    let compressedSize: Int
    let uncompressedSize: Int
    let localHeaderOffset: Int
}

enum ZipError: LocalizedError {
    case invalidArchive(String)
    case unsupported(String)
    case unsafePath(String)
    case crcMismatch(String)
    case tooLarge(String)

    var errorDescription: String? {
        switch self {
        case .invalidArchive(let reason): return "INVALID_ARCHIVE: \(reason)"
        case .unsupported(let reason): return "UNSUPPORTED_ARCHIVE: \(reason)"
        case .unsafePath(let path): return "UNSAFE_PATH: \(path)"
        case .crcMismatch(let name): return "CRC_MISMATCH: \(name)"
        case .tooLarge(let reason): return "ARCHIVE_TOO_LARGE: \(reason)"
        }
    }
}

final class ZipArchive {
    private static let maxEntries = 5000
    private static let maxTotalBytes = 200 * 1024 * 1024

    private let bytes: [UInt8]
    let entries: [ZipEntry]

    init(data: Data) throws {
        self.bytes = [UInt8](data)
        self.entries = try ZipArchive.parseEntries(bytes)
    }

    // MARK: - 解析

    private static func u16(_ bytes: [UInt8], _ offset: Int) -> UInt16 {
        guard offset + 1 < bytes.count else { return 0 }
        return UInt16(bytes[offset]) | (UInt16(bytes[offset + 1]) << 8)
    }

    private static func u32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        guard offset + 3 < bytes.count else { return 0 }
        return UInt32(bytes[offset])
            | (UInt32(bytes[offset + 1]) << 8)
            | (UInt32(bytes[offset + 2]) << 16)
            | (UInt32(bytes[offset + 3]) << 24)
    }

    private static func parseEntries(_ bytes: [UInt8]) throws -> [ZipEntry] {
        guard bytes.count > 22 else {
            throw ZipError.invalidArchive("文件太小，不是 zip")
        }
        // 从文件尾部向前找 EOCD（0x06054b50），最多找 64KB + 22
        var eocd = -1
        let lowerBound = max(0, bytes.count - 66_000)
        var index = bytes.count - 22
        while index >= lowerBound {
            if bytes[index] == 0x50, bytes[index + 1] == 0x4b, bytes[index + 2] == 0x05, bytes[index + 3] == 0x06 {
                eocd = index
                break
            }
            index -= 1
        }
        guard eocd >= 0 else {
            throw ZipError.invalidArchive("找不到 EOCD（可能不是 zip 或被截断）")
        }

        let totalEntries = Int(u16(bytes, eocd + 10))
        let centralOffset = Int(u32(bytes, eocd + 16))
        guard totalEntries > 0 else {
            throw ZipError.invalidArchive("zip 里没有文件")
        }
        guard totalEntries <= maxEntries else {
            throw ZipError.tooLarge("条目数 \(totalEntries) > \(maxEntries)")
        }
        guard centralOffset > 0, centralOffset < bytes.count else {
            throw ZipError.invalidArchive("中央目录偏移非法")
        }

        var result: [ZipEntry] = []
        result.reserveCapacity(totalEntries)
        var pointer = centralOffset
        for _ in 0..<totalEntries {
            guard pointer + 46 <= bytes.count else {
                throw ZipError.invalidArchive("中央目录被截断")
            }
            guard u32(bytes, pointer) == 0x0201_4b50 else {
                throw ZipError.invalidArchive("中央目录签名不对 @\(pointer)")
            }
            let method = u16(bytes, pointer + 10)
            let crc = u32(bytes, pointer + 16)
            let compressedSize = Int(u32(bytes, pointer + 20))
            let uncompressedSize = Int(u32(bytes, pointer + 24))
            let nameLength = Int(u16(bytes, pointer + 28))
            let extraLength = Int(u16(bytes, pointer + 30))
            let commentLength = Int(u16(bytes, pointer + 32))
            let localOffset = Int(u32(bytes, pointer + 42))
            guard pointer + 46 + nameLength <= bytes.count else {
                throw ZipError.invalidArchive("文件名被截断")
            }
            let nameBytes = bytes[(pointer + 46)..<(pointer + 46 + nameLength)]
            guard let name = String(bytes: nameBytes, encoding: .utf8) else {
                throw ZipError.invalidArchive("文件名不是合法 UTF-8")
            }
            if compressedSize == 0xFFFF_FFFF || uncompressedSize == 0xFFFF_FFFF {
                throw ZipError.unsupported("不支持 ZIP64（\(name)）")
            }
            result.append(ZipEntry(
                name: name,
                method: method,
                crc32: crc,
                compressedSize: compressedSize,
                uncompressedSize: uncompressedSize,
                localHeaderOffset: localOffset
            ))
            pointer += 46 + nameLength + extraLength + commentLength
        }
        return result
    }

    // MARK: - 解压

    @discardableResult
    func extractAll(to directory: URL) throws -> (files: Int, bytes: Int) {
        var fileCount = 0
        var totalBytes = 0
        let root = directory.standardizedFileURL

        for entry in entries {
            try ZipArchive.validate(name: entry.name)
            let target = root.appendingPathComponent(entry.name)
            let standardized = target.standardizedFileURL
            guard standardized.path == root.path || standardized.path.hasPrefix(root.path + "/") else {
                throw ZipError.unsafePath(entry.name)
            }

            if entry.name.hasSuffix("/") {
                try FileManager.default.createDirectory(at: standardized, withIntermediateDirectories: true)
                continue
            }
            try FileManager.default.createDirectory(
                at: standardized.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )

            let payload = try payload(for: entry)
            if entry.uncompressedSize != payload.count {
                throw ZipError.invalidArchive("\(entry.name) 解压后大小 \(payload.count) != \(entry.uncompressedSize)")
            }
            if ZipArchive.crc32(payload) != entry.crc32 {
                throw ZipError.crcMismatch(entry.name)
            }
            try payload.write(to: standardized, options: .atomic)
            fileCount += 1
            totalBytes += payload.count
            if totalBytes > ZipArchive.maxTotalBytes {
                throw ZipError.tooLarge("解压后超过 \(ZipArchive.maxTotalBytes) 字节")
            }
        }

        let index = root.appendingPathComponent("index.html")
        guard FileManager.default.fileExists(atPath: index.path) else {
            throw ZipError.invalidArchive("包里没有 index.html，拒绝安装")
        }
        return (fileCount, totalBytes)
    }

    private func payload(for entry: ZipEntry) throws -> Data {
        let p = entry.localHeaderOffset
        guard p + 30 <= bytes.count, ZipArchive.u32(bytes, p) == 0x0403_4b50 else {
            throw ZipError.invalidArchive("本地文件头签名不对（\(entry.name)）")
        }
        let nameLength = Int(ZipArchive.u16(bytes, p + 26))
        let extraLength = Int(ZipArchive.u16(bytes, p + 28))
        let start = p + 30 + nameLength + extraLength
        let end = start + entry.compressedSize
        guard start >= 0, end <= bytes.count, start <= end else {
            throw ZipError.invalidArchive("压缩数据越界（\(entry.name)）")
        }
        let compressed = Array(bytes[start..<end])

        switch entry.method {
        case 0:
            return Data(compressed)
        case 8:
            return try ZipArchive.inflate(compressed, expected: entry.uncompressedSize, name: entry.name)
        default:
            throw ZipError.unsupported("压缩方法 \(entry.method)（\(entry.name)）")
        }
    }

    /// 原始 DEFLATE（RFC 1951）解压：Compression 的 `.zlib` 算法就是 raw deflate（无 zlib 头）。
    private static func inflate(_ input: [UInt8], expected: Int, name: String) throws -> Data {
        if expected == 0 { return Data() }
        var output = [UInt8](repeating: 0, count: expected)
        let written: Int = output.withUnsafeMutableBufferPointer { outBuffer -> Int in
            guard let outBase = outBuffer.baseAddress else { return 0 }
            return input.withUnsafeBufferPointer { inBuffer -> Int in
                guard let inBase = inBuffer.baseAddress else { return 0 }
                return compression_decode_buffer(
                    outBase, expected,
                    inBase, input.count,
                    nil, COMPRESSION_ZLIB
                )
            }
        }
        guard written == expected else {
            throw ZipError.invalidArchive("\(name) 解压失败（\(written)/\(expected) 字节）")
        }
        return Data(output)
    }

    private static func validate(name: String) throws {
        if name.isEmpty || name.hasPrefix("/") || name.hasPrefix("\\") {
            throw ZipError.unsafePath(name)
        }
        if name.contains(":") || name.contains("\u{0}") {
            throw ZipError.unsafePath(name)
        }
        for part in name.replacingOccurrences(of: "\\", with: "/").split(separator: "/") where part == ".." {
            throw ZipError.unsafePath(name)
        }
    }

    // MARK: - CRC32

    private static let crcTable: [UInt32] = {
        var table = [UInt32](repeating: 0, count: 256)
        for i in 0..<256 {
            var value = UInt32(i)
            for _ in 0..<8 {
                value = (value & 1) == 1 ? (0xEDB8_8320 ^ (value >> 1)) : (value >> 1)
            }
            table[i] = value
        }
        return table
    }()

    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            let index = Int((crc ^ UInt32(byte)) & 0xFF)
            crc = crcTable[index] ^ (crc >> 8)
        }
        return crc ^ 0xFFFF_FFFF
    }
}
