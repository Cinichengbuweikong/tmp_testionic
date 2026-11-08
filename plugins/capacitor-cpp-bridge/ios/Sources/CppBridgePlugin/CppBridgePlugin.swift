import Foundation
import Capacitor
import CppCore

/**
 * CppBridge —— iOS 侧插件（jsName = "CppBridge"）。
 *
 * 这是 **C/C++ 桥模板**：把 WebView 的调用转发到 Swift，再转发到共享 C/C++ 核心。
 * 业务逻辑写在 `shared/core/`（Android/iOS 共用），这里只做参数校验与句柄管理。
 *
 * Swift 侧能直接 `import CppCore` 调 C 函数，是因为 `cppcore.h` 用的是纯 C 接口（`extern "C"`）。
 * 不要把 C++ 的 `std::string` / 模板暴露到边界上——Swift 用不了。
 *
 * ⚠️ 句柄 API 是 danger 的：handle 是 native 指针（`Int(bitPattern:)`），必须 `freeBuffer`，
 *    进程重启失效，不要并发写同一 handle。
 */
@objc(CppBridgePlugin)
public class CppBridgePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "CppBridgePlugin"
    public let jsName = "CppBridge"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getCoreInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "add", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cAdd", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cStrlen", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "allocBuffer", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "fillBuffer", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "sumBuffer", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "freeBuffer", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getBufferStats", returnType: CAPPluginReturnPromise)
    ]

    /// handle（指针数值） → 分配大小（字节）；用于统计与重复释放检查。
    private var buffers: [Int64: Int64] = [:]
    private var allocatedBytes: Int64 = 0
    private let lock = NSLock()

    // ================================================================ 示例 API

    @objc func getCoreInfo(_ call: CAPPluginCall) {
        var info: [String: Any] = [
            "platform": "ios",
            "loaded": true,
            "library": "CppCore（静态链接进 App）"
        ]
        if let buildInfo = cppcore_build_info() {
            info["buildInfo"] = String(cString: buildInfo)
        }
        call.resolve(info)
    }

    @objc func add(_ call: CAPPluginCall) {
        let a = Int32(truncatingIfNeeded: readInt64(call, "a") ?? 0)
        let b = Int32(truncatingIfNeeded: readInt64(call, "b") ?? 0)
        call.resolve([
            "a": Int(a),
            "b": Int(b),
            "result": Int(cppcore_add(a, b)),
            "impl": "cpp"
        ])
    }

    @objc func cAdd(_ call: CAPPluginCall) {
        let a = Int32(truncatingIfNeeded: readInt64(call, "a") ?? 0)
        let b = Int32(truncatingIfNeeded: readInt64(call, "b") ?? 0)
        call.resolve([
            "a": Int(a),
            "b": Int(b),
            "result": Int(cppcore_c_add(a, b)),
            "impl": "c"
        ])
    }

    @objc func cStrlen(_ call: CAPPluginCall) {
        let text = call.getString("text", "")
        let length = text.withCString { cppcore_c_strlen($0) }
        call.resolve([
            "text": text,
            "length": Int(length),
            "impl": "c"
        ])
    }

    // ================================================================ ⚠️ danger：句柄 API

    @objc func allocBuffer(_ call: CAPPluginCall) {
        let size = readInt64(call, "size") ?? 0
        guard size > 0, size % 4 == 0 else {
            call.reject("INVALID_SIZE: size must be a positive multiple of 4")
            return
        }
        guard let raw = cppcore_buffer_alloc(size) else {
            call.reject("ALLOC_FAILED: native malloc returned null (size=\(size))")
            return
        }
        let handle = Int64(Int(bitPattern: UnsafeRawPointer(raw)))
        lock.lock()
        buffers[handle] = size
        allocatedBytes += size
        let totalBuffers = buffers.count
        let totalBytes = allocatedBytes
        lock.unlock()

        call.resolve([
            "handle": handle,
            "size": size,
            "totalBuffers": totalBuffers,
            "totalBytes": totalBytes
        ])
    }

    @objc func fillBuffer(_ call: CAPPluginCall) {
        guard let handle = readInt64(call, "handle"), let size = bufferSize(handle) else {
            call.reject("UNKNOWN_HANDLE: 句柄不存在或已释放")
            return
        }
        let value = Int32(truncatingIfNeeded: readInt64(call, "value") ?? 0)
        let raw = UnsafeMutableRawPointer(bitPattern: Int(handle))
        let rc = cppcore_buffer_fill(raw, size, value)
        guard rc == 0 else {
            call.reject("FILL_FAILED: cppcore_buffer_fill returned \(rc)")
            return
        }
        call.resolve(["ok": true, "handle": handle, "size": size, "value": Int(value)])
    }

    @objc func sumBuffer(_ call: CAPPluginCall) {
        guard let handle = readInt64(call, "handle"), let size = bufferSize(handle) else {
            call.reject("UNKNOWN_HANDLE: 句柄不存在或已释放")
            return
        }
        let raw = UnsafeRawPointer(bitPattern: Int(handle))
        var sum: Int64 = 0
        let rc = cppcore_buffer_sum(raw, size, &sum)
        guard rc == 0 else {
            call.reject("SUM_FAILED: cppcore_buffer_sum returned \(rc)")
            return
        }
        call.resolve(["handle": handle, "size": size, "sum": sum])
    }

    @objc func freeBuffer(_ call: CAPPluginCall) {
        guard let handle = readInt64(call, "handle") else {
            call.reject("missing handle")
            return
        }
        lock.lock()
        let size = buffers.removeValue(forKey: handle)
        if let size = size {
            allocatedBytes -= size
        }
        let totalBuffers = buffers.count
        let totalBytes = allocatedBytes
        lock.unlock()

        guard let freedSize = size else {
            call.resolve(["freed": false, "reason": "UNKNOWN_OR_ALREADY_FREED"])
            return
        }
        cppcore_buffer_free(UnsafeMutableRawPointer(bitPattern: Int(handle)))
        call.resolve([
            "freed": true,
            "size": freedSize,
            "totalBuffers": totalBuffers,
            "totalBytes": totalBytes
        ])
    }

    @objc func getBufferStats(_ call: CAPPluginCall) {
        lock.lock()
        let count = buffers.count
        let bytes = allocatedBytes
        lock.unlock()
        call.resolve(["count": count, "bytes": bytes])
    }

    // ================================================================ 工具

    /// 读取 64 位整数参数。
    ///
    /// 句柄是 native 指针数值（可能超过 Int32），不能依赖 getInt()。
    /// `getValue(_:)` 返回的是原始 JSValue（JSON 数字会被桥接成 NSNumber/Int/Double）。
    private func readInt64(_ call: CAPPluginCall, _ key: String) -> Int64? {
        guard let raw = call.getValue(key) else {
            return nil
        }
        if let number = raw as? NSNumber {
            return number.int64Value
        }
        if let value = raw as? Int {
            return Int64(value)
        }
        if let value = raw as? Double {
            return Int64(exactly: value.rounded())
        }
        if let text = raw as? String {
            return Int64(text)
        }
        return nil
    }

    private func bufferSize(_ handle: Int64) -> Int64? {
        lock.lock()
        defer { lock.unlock() }
        return buffers[handle]
    }
}
