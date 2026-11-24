import AppTrackingTransparency
import AVFoundation
import Capacitor
import Contacts
import CoreBluetooth
import CoreLocation
import CoreMotion
import EventKit
import Foundation
import Photos
import Speech
import UIKit
import UserNotifications

/**
 * 权限插件模板（iOS 侧）。
 *
 * ============================ 这是一个「模板」============================
 * 和 Android 侧一样：{@link descriptors} 这张表就是全部可扩展点。加一个权限 =
 * 在表里加**一条** `PermissionDescriptor`（必要时补两个小方法）+ 在宿主 Info.plist 加对应的
 * usage description；`check() / request() / getSupported()` 会自动支持它。
 *
 * 设计约定：
 * - `check` 只读状态、**绝不弹窗**（`request` 才会弹）；
 * - iOS **一次只能弹一个**授权框，所以 `request()` 是串行执行的；
 * - 有些框架没有"请求"回调（蓝牙、运动），只能触发后等一小会儿再读状态，这类细节写在
 *   对应 descriptor 的 request 实现里，并在 `note` 里说明；
 * - 缺 Info.plist usage description 时系统会**直接崩溃**（不是返回 denied），所以
 *   `requires` 字段必须写清楚。
 */
@objc(PermissionsPlugin)
public class PermissionsPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "PermissionsPlugin"
    public let jsName = "Permissions"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "check", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "request", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getSupported", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openAppSettings", returnType: CAPPluginReturnPromise)
    ]

    private var locationRequester: LocationPermissionRequester?
    private let contactStore = CNContactStore()

    // MARK: - 权限注册表（模板的核心）

    /// 一条权限定义。`check`/`request` 都接收 plugin 实例，避免闭包强引用 self 造成循环引用。
    private struct PermissionDescriptor {
        let id: String
        let supported: Bool
        let requires: [String]
        let note: String?
        let check: (PermissionsPlugin) -> (state: String, detail: String)
        let request: (PermissionsPlugin, @escaping (String, String) -> Void) -> Void
    }

    private static let descriptors: [PermissionDescriptor] = [
        PermissionDescriptor(
            id: "camera",
            supported: true,
            requires: ["Info.plist: NSCameraUsageDescription"],
            note: nil,
            check: { $0.avState(.video, "AVCaptureDevice(.video)") },
            request: { $0.requestAV(.video, "AVCaptureDevice.requestAccess(.video)", $1) }
        ),
        PermissionDescriptor(
            id: "microphone",
            supported: true,
            requires: ["Info.plist: NSMicrophoneUsageDescription"],
            note: nil,
            check: { $0.avState(.audio, "AVCaptureDevice(.audio)") },
            request: { $0.requestAV(.audio, "AVCaptureDevice.requestAccess(.audio)", $1) }
        ),
        PermissionDescriptor(
            id: "location",
            supported: true,
            requires: [
                "Info.plist: NSLocationWhenInUseUsageDescription",
                "（后台定位还需要 NSLocationAlwaysAndWhenInUseUsageDescription + UIBackgroundModes: location）"
            ],
            note: "locationCoarse 与 location 共用同一个 iOS 权限（精度由用户在设置里选择）",
            check: { $0.locationState() },
            request: { $0.requestLocation($1) }
        ),
        PermissionDescriptor(
            id: "locationCoarse",
            supported: true,
            requires: ["Info.plist: NSLocationWhenInUseUsageDescription"],
            note: "iOS 14+ 用户可以选择「精确」或「大致位置」，可用 CLLocationManager.accuracyAuthorization 读取",
            check: { $0.locationState() },
            request: { $0.requestLocation($1) }
        ),
        PermissionDescriptor(
            id: "notifications",
            supported: true,
            requires: ["无需 Info.plist"],
            note: nil,
            check: { $0.notificationsState() },
            request: { $0.requestNotifications($1) }
        ),
        PermissionDescriptor(
            id: "photos",
            supported: true,
            requires: ["Info.plist: NSPhotoLibraryUsageDescription"],
            note: "iOS 14+ 会返回 limited（用户只选中了部分照片）",
            check: { $0.photosState() },
            request: { $0.requestPhotos($1) }
        ),
        PermissionDescriptor(
            id: "contacts",
            supported: true,
            requires: ["Info.plist: NSContactsUsageDescription"],
            note: nil,
            check: { $0.contactsState() },
            request: { $0.requestContacts($1) }
        ),
        PermissionDescriptor(
            id: "calendar",
            supported: true,
            requires: ["Info.plist: NSCalendarsUsageDescription"],
            note: nil,
            check: { $0.eventKitState(.event) },
            request: { $0.requestEventKit(.event, $1) }
        ),
        PermissionDescriptor(
            id: "reminders",
            supported: true,
            requires: ["Info.plist: NSRemindersUsageDescription"],
            note: nil,
            check: { $0.eventKitState(.reminder) },
            request: { $0.requestEventKit(.reminder, $1) }
        ),
        PermissionDescriptor(
            id: "bluetooth",
            supported: true,
            requires: ["Info.plist: NSBluetoothAlwaysUsageDescription"],
            note: "iOS 没有蓝牙授权的 completion，只能实例化 CBCentralManager 触发弹窗后读状态",
            check: { $0.bluetoothState() },
            request: { $0.requestBluetooth($1) }
        ),
        PermissionDescriptor(
            id: "motion",
            supported: true,
            requires: ["Info.plist: NSMotionUsageDescription"],
            note: "iOS 没有运动权限的 completion，靠 startActivityUpdates 触发弹窗",
            check: { $0.motionState() },
            request: { $0.requestMotion($1) }
        ),
        PermissionDescriptor(
            id: "tracking",
            supported: true,
            requires: ["Info.plist: NSUserTrackingUsageDescription"],
            note: "iOS 14+ ATT（App 跟踪透明度）",
            check: { $0.trackingState() },
            request: { $0.requestTracking($1) }
        ),
        PermissionDescriptor(
            id: "speech",
            supported: true,
            requires: [
                "Info.plist: NSSpeechRecognitionUsageDescription",
                "Info.plist: NSMicrophoneUsageDescription"
            ],
            note: nil,
            check: { $0.speechState() },
            request: { $0.requestSpeech($1) }
        ),
        PermissionDescriptor(
            id: "storage",
            supported: false,
            requires: [],
            note: "iOS 使用沙盒文件系统，没有「存储权限」概念",
            check: { _ in ("unsupported", "iOS 没有「存储权限」概念（沙盒文件系统）") },
            request: { _, done in done("unsupported", "iOS 没有「存储权限」概念（沙盒文件系统）") }
        ),
        PermissionDescriptor(
            id: "activityRecognition",
            supported: false,
            requires: [],
            note: "iOS 没有对应的运行时权限；运动数据用 motion",
            check: { _ in ("unsupported", "iOS 没有对应的运行时权限；运动数据用 motion") },
            request: { _, done in done("unsupported", "iOS 没有对应的运行时权限；运动数据用 motion") }
        ),
        PermissionDescriptor(
            id: "bodySensors",
            supported: false,
            requires: [],
            note: "iOS 没有对应的运行时权限",
            check: { _ in ("unsupported", "iOS 没有 bodySensors 权限") },
            request: { _, done in done("unsupported", "iOS 没有 bodySensors 权限") }
        )
        // ⬇️ 新增权限：照上面的格式加一条（必要时在下面补两个小方法）
    ]

    // MARK: - check

    @objc public func check(_ call: CAPPluginCall) {
        let ids = call.getArray("permissions", String.self) ?? []
        var results: [String: String] = [:]
        var details: [String: String] = [:]
        for id in ids {
            let (state, detail) = stateOf(id)
            results[id] = state
            details[id] = detail
        }
        call.resolve(["results": results, "details": details])
    }

    private func stateOf(_ id: String) -> (String, String) {
        guard let descriptor = Self.descriptors.first(where: { $0.id == id }) else {
            return ("unsupported", "未知权限 id：\(id)（检查 descriptors 表，或参考 TEMPLATE.md 添加）")
        }
        return descriptor.check(self)
    }

    // MARK: - request（串行）

    @objc public func request(_ call: CAPPluginCall) {
        let ids = call.getArray("permissions", String.self) ?? []
        var results: [String: String] = [:]
        var details: [String: String] = [:]
        var index = 0

        func next() {
            guard index < ids.count else {
                call.resolve(["results": results, "details": details])
                return
            }
            let id = ids[index]
            index += 1
            requestOne(id) { state, detail in
                results[id] = state
                details[id] = detail
                // iOS 的系统弹窗必须一个一个来：上一个回调后再弹下一个
                DispatchQueue.main.async { next() }
            }
        }
        DispatchQueue.main.async { next() }
    }

    private func requestOne(_ id: String, completion: @escaping (String, String) -> Void) {
        guard let descriptor = Self.descriptors.first(where: { $0.id == id }) else {
            let (state, detail) = stateOf(id)
            completion(state, detail)
            return
        }
        descriptor.request(self, completion)
    }

    // MARK: - getSupported

    @objc public func getSupported(_ call: CAPPluginCall) {
        let items: [[String: Any]] = Self.descriptors.map { descriptor in
            var item: [String: Any] = [
                "id": descriptor.id,
                "supported": descriptor.supported,
                "requires": descriptor.requires
            ]
            if let note = descriptor.note { item["note"] = note }
            return item
        }
        call.resolve(["platform": "ios", "permissions": items])
    }

    // MARK: - openAppSettings

    @objc public func openAppSettings(_ call: CAPPluginCall) {
        guard let url = URL(string: UIApplication.openSettingsURLString) else {
            call.resolve(["opened": false])
            return
        }
        DispatchQueue.main.async {
            UIApplication.shared.open(url, options: [:]) { opened in
                call.resolve(["opened": opened])
            }
        }
    }

    // MARK: - 各权限的 check / request 实现
    //
    // ⬇️ 新增权限时，通常在这里加一对（check + request）小方法，然后在 descriptors 里引用。

    private func avState(_ mediaType: AVMediaType, _ source: String) -> (String, String) {
        let status = AVCaptureDevice.authorizationStatus(for: mediaType)
        let state: String
        switch status {
        case .authorized: state = "granted"
        case .denied: state = "denied"
        case .restricted: state = "restricted"
        case .notDetermined: state = "prompt"
        @unknown default: state = "denied"
        }
        return (state, "\(source) = \(status.rawValue)")
    }

    private func requestAV(_ mediaType: AVMediaType, _ source: String,
                           _ completion: @escaping (String, String) -> Void) {
        ensureMain {
            AVCaptureDevice.requestAccess(for: mediaType) { granted in
                completion(granted ? "granted" : "denied", source)
            }
        }
    }

    private func locationState() -> (String, String) {
        let status = onMainSync { CLLocationManager().authorizationStatus }
        return (mapLocation(status), "CLLocationManager.authorizationStatus = \(status.rawValue)")
    }

    private func requestLocation(_ completion: @escaping (String, String) -> Void) {
        ensureMain {
            let requester = LocationPermissionRequester { state in
                completion(state, "CLLocationManager.requestWhenInUseAuthorization")
                self.locationRequester = nil
            }
            self.locationRequester = requester
            requester.request()
        }
    }

    private func notificationsState() -> (String, String) {
        let semaphore = DispatchSemaphore(value: 0)
        var raw = -1
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            raw = settings.authorizationStatus.rawValue
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 3)
        switch raw {
        case UNAuthorizationStatus.authorized.rawValue, UNAuthorizationStatus.provisional.rawValue,
             UNAuthorizationStatus.ephemeral.rawValue:
            return ("granted", "UNUserNotificationCenter.authorizationStatus = \(raw)")
        case UNAuthorizationStatus.denied.rawValue:
            return ("denied", "UNUserNotificationCenter.authorizationStatus = \(raw)")
        case UNAuthorizationStatus.notDetermined.rawValue:
            return ("prompt", "UNUserNotificationCenter.authorizationStatus = notDetermined")
        default:
            return ("unsupported", "无法读取通知设置（\(raw)）")
        }
    }

    private func requestNotifications(_ completion: @escaping (String, String) -> Void) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            if let error = error {
                completion("denied", "requestAuthorization 失败：\(error.localizedDescription)")
            } else {
                completion(granted ? "granted" : "denied", "UNUserNotificationCenter.requestAuthorization")
            }
        }
    }

    private func photosState() -> (String, String) {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        return (mapPhotos(status), "PHPhotoLibrary.authorizationStatus(for: .readWrite) = \(status.rawValue)")
    }

    private func requestPhotos(_ completion: @escaping (String, String) -> Void) {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
            completion(self.mapPhotos(status), "PHPhotoLibrary.requestAuthorization(for: .readWrite)")
        }
    }

    private func contactsState() -> (String, String) {
        let status = CNContactStore.authorizationStatus(for: .contacts)
        return (mapContacts(status), "CNContactStore.authorizationStatus = \(status.rawValue)")
    }

    private func requestContacts(_ completion: @escaping (String, String) -> Void) {
        contactStore.requestAccess(for: .contacts) { granted, error in
            if let error = error {
                completion("denied", "contacts requestAccess 失败：\(error.localizedDescription)")
            } else {
                completion(granted ? "granted" : "denied", "CNContactStore.requestAccess")
            }
        }
    }

    private func eventKitState(_ entity: EKEntityType) -> (String, String) {
        let status = EKEventStore.authorizationStatus(for: entity)
        return (mapEventKit(status.rawValue), "EKEventStore authorizationStatus = \(status.rawValue)")
    }

    private func requestEventKit(_ entity: EKEntityType, _ completion: @escaping (String, String) -> Void) {
        let store = EKEventStore()
        if #available(iOS 17.0, *) {
            if entity == .event {
                store.requestFullAccessToEvents { granted, _ in
                    completion(granted ? "granted" : "denied", "EKEventStore.requestFullAccessToEvents")
                }
            } else {
                store.requestFullAccessToReminders { granted, _ in
                    completion(granted ? "granted" : "denied", "EKEventStore.requestFullAccessToReminders")
                }
            }
        } else {
            store.requestAccess(to: entity) { granted, _ in
                completion(granted ? "granted" : "denied", "EKEventStore.requestAccess(to:)")
            }
        }
    }

    private func bluetoothState() -> (String, String) {
        let status = CBManager.authorization
        return (mapBluetooth(status), "CBManager.authorization = \(status.rawValue)")
    }

    private func requestBluetooth(_ completion: @escaping (String, String) -> Void) {
        // 实例化 CBCentralManager 会触发系统蓝牙授权框；iOS 不提供 completion，只能等一小会儿再读状态
        ensureMain {
            _ = BluetoothPermissionRequester.shared
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                completion(self.mapBluetooth(CBManager.authorization), "CBManager.authorization（授权框已弹出）")
            }
        }
    }

    private func motionState() -> (String, String) {
        let status = CMMotionActivityManager.authorizationStatus()
        return (mapMotion(status), "CMMotionActivityManager.authorizationStatus = \(status.rawValue)")
    }

    private func requestMotion(_ completion: @escaping (String, String) -> Void) {
        ensureMain {
            let manager = CMMotionActivityManager()
            manager.startActivityUpdates(to: .main) { _ in }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                manager.stopActivityUpdates()
                completion(self.mapMotion(CMMotionActivityManager.authorizationStatus()),
                           "CMMotionActivityManager.startActivityUpdates（授权框已弹出）")
            }
        }
    }

    private func trackingState() -> (String, String) {
        let status = ATTrackingManager.trackingAuthorizationStatus
        return (mapTracking(status), "ATTrackingManager = \(status.rawValue)")
    }

    private func requestTracking(_ completion: @escaping (String, String) -> Void) {
        if #available(iOS 14, *) {
            ATTrackingManager.requestTrackingAuthorization { status in
                completion(self.mapTracking(status), "ATTrackingManager.requestTrackingAuthorization")
            }
        } else {
            completion("unsupported", "需要 iOS 14+")
        }
    }

    private func speechState() -> (String, String) {
        let status = SFSpeechRecognizer.authorizationStatus()
        return (mapSpeech(status), "SFSpeechRecognizer = \(status.rawValue)")
    }

    private func requestSpeech(_ completion: @escaping (String, String) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            completion(self.mapSpeech(status), "SFSpeechRecognizer.requestAuthorization")
        }
    }

    // MARK: - 状态映射（新增权限时优先复用这些）

    private func mapLocation(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .authorizedAlways, .authorizedWhenInUse: return "granted"
        case .denied: return "denied"
        case .restricted: return "restricted"
        case .notDetermined: return "prompt"
        @unknown default: return "denied"
        }
    }

    private func mapPhotos(_ status: PHAuthorizationStatus) -> String {
        switch status {
        case .authorized: return "granted"
        case .limited: return "limited"
        case .denied: return "denied"
        case .restricted: return "restricted"
        case .notDetermined: return "prompt"
        @unknown default: return "denied"
        }
    }

    /// 用 rawValue 判断，避免 iOS 18 才有的 `.limited` 在低版本 SDK 上触发可用性检查。
    private func mapContacts(_ status: CNAuthorizationStatus) -> String {
        switch status.rawValue {
        case 0: return "prompt"
        case 1: return "restricted"
        case 2: return "denied"
        case 3: return "granted"
        case 4: return "limited"
        default: return "denied"
        }
    }

    /// EventKit 用 rawValue 判断，避免 iOS 17 的 `.fullAccess` / `.writeOnly` 可用性分支。
    private func mapEventKit(_ raw: Int) -> String {
        switch raw {
        case 0: return "prompt"
        case 1: return "restricted"
        case 2: return "denied"
        case 3: return "granted"
        case 4: return "limited"
        default: return "denied"
        }
    }

    private func mapBluetooth(_ status: CBManagerAuthorization) -> String {
        switch status {
        case .allowedAlways: return "granted"
        case .denied: return "denied"
        case .restricted: return "restricted"
        case .notDetermined: return "prompt"
        @unknown default: return "denied"
        }
    }

    private func mapMotion(_ status: CMAuthorizationStatus) -> String {
        switch status {
        case .authorized: return "granted"
        case .denied: return "denied"
        case .restricted: return "restricted"
        case .notDetermined: return "prompt"
        @unknown default: return "denied"
        }
    }

    private func mapTracking(_ status: ATTrackingManager.AuthorizationStatus) -> String {
        switch status {
        case .authorized: return "granted"
        case .denied: return "denied"
        case .restricted: return "restricted"
        case .notDetermined: return "prompt"
        @unknown default: return "denied"
        }
    }

    private func mapSpeech(_ status: SFSpeechRecognizerAuthorizationStatus) -> String {
        switch status {
        case .authorized: return "granted"
        case .denied: return "denied"
        case .restricted: return "restricted"
        case .notDetermined: return "prompt"
        @unknown default: return "denied"
        }
    }

    // MARK: - 线程工具

    private func ensureMain(_ body: @escaping () -> Void) {
        if Thread.isMainThread {
            body()
        } else {
            DispatchQueue.main.async(execute: body)
        }
    }

    private func onMainSync<T>(_ body: () -> T) -> T {
        if Thread.isMainThread {
            return body()
        }
        return DispatchQueue.main.sync(execute: body)
    }
}

// MARK: - 定位授权（需要 CLLocationManagerDelegate 回调）

final class LocationPermissionRequester: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let completion: (String) -> Void

    init(completion: @escaping (String) -> Void) {
        self.completion = completion
        super.init()
        manager.delegate = self
    }

    func request() {
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
        } else {
            finish(manager.authorizationStatus)
        }
    }

    private func finish(_ status: CLAuthorizationStatus) {
        let state: String
        switch status {
        case .authorizedAlways, .authorizedWhenInUse: state = "granted"
        case .denied: state = "denied"
        case .restricted: state = "restricted"
        case .notDetermined: state = "prompt"
        @unknown default: state = "denied"
        }
        completion(state)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard manager.authorizationStatus != .notDetermined else { return }
        finish(manager.authorizationStatus)
    }
}

// MARK: - 蓝牙授权

/// 只是为了持有 CBCentralManager 实例（实例化即会触发系统蓝牙授权框）。
final class BluetoothPermissionRequester {
    static let shared = BluetoothPermissionRequester()
    private let manager: CBCentralManager

    private init() {
        manager = CBCentralManager(delegate: nil, queue: .main)
    }
}
