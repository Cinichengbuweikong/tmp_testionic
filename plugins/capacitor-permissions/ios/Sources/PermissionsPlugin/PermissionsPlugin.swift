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
 * 权限插件（iOS）。
 *
 * 统一 id → 系统框架。`check()` 只读状态；`request()` 会弹系统授权框（一次只能弹一个，
 * 所以是串行执行的）。
 *
 * ⚠️ 宿主 Info.plist 必须提供对应的 usage description，否则调用系统 API 会**直接崩溃**
 * （不是返回 denied）。完整列表见 README 与 `getSupported().permissions[].requires`。
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
        switch id {
        case "camera":
            return describe(AVCaptureDevice.authorizationStatus(for: .video), "AVCaptureDevice(.video)", id)
        case "microphone":
            return describe(AVCaptureDevice.authorizationStatus(for: .audio), "AVCaptureDevice(.audio)", id)
        case "location", "locationCoarse":
            let status = onMainSync { CLLocationManager().authorizationStatus }
            return (mapLocation(status), "CLLocationManager.authorizationStatus = \(status.rawValue)")
        case "notifications":
            return notificationsState()
        case "photos":
            let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            return (mapPhotos(status), "PHPhotoLibrary.authorizationStatus(for: .readWrite) = \(status.rawValue)")
        case "contacts":
            let status = CNContactStore.authorizationStatus(for: .contacts)
            return (mapContacts(status), "CNContactStore.authorizationStatus = \(status.rawValue)")
        case "calendar":
            let status = EKEventStore.authorizationStatus(for: .event)
            return (mapEventKit(status.rawValue), "EKEventStore(.event) = \(status.rawValue)")
        case "reminders":
            let status = EKEventStore.authorizationStatus(for: .reminder)
            return (mapEventKit(status.rawValue), "EKEventStore(.reminder) = \(status.rawValue)")
        case "bluetooth":
            let status = CBManager.authorization
            return (mapBluetooth(status), "CBManager.authorization = \(status.rawValue)")
        case "motion":
            let status = CMMotionActivityManager.authorizationStatus()
            return (mapMotion(status), "CMMotionActivityManager.authorizationStatus = \(status.rawValue)")
        case "tracking":
            let status = ATTrackingManager.trackingAuthorizationStatus
            return (mapTracking(status), "ATTrackingManager = \(status.rawValue)")
        case "speech":
            let status = SFSpeechRecognizer.authorizationStatus()
            return (mapSpeech(status), "SFSpeechRecognizer = \(status.rawValue)")
        case "storage", "activityRecognition", "bodySensors":
            return ("unsupported", "iOS 没有这个权限（\(id)）")
        default:
            return ("unsupported", "未知权限 id：\(id)")
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

    // MARK: - request

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
                DispatchQueue.main.async { next() }
            }
        }
        DispatchQueue.main.async { next() }
    }

    private func requestOne(_ id: String, completion: @escaping (String, String) -> Void) {
        switch id {
        case "camera":
            ensureMain {
                AVCaptureDevice.requestAccess(for: .video) { granted in
                    completion(granted ? "granted" : "denied", "AVCaptureDevice.requestAccess(.video)")
                }
            }
        case "microphone":
            ensureMain {
                AVCaptureDevice.requestAccess(for: .audio) { granted in
                    completion(granted ? "granted" : "denied", "AVCaptureDevice.requestAccess(.audio)")
                }
            }
        case "location", "locationCoarse":
            ensureMain {
                let requester = LocationPermissionRequester { state in
                    completion(state, "CLLocationManager.requestWhenInUseAuthorization")
                    self.locationRequester = nil
                }
                self.locationRequester = requester
                requester.request()
            }
        case "notifications":
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
                if let error = error {
                    completion("denied", "requestAuthorization 失败：\(error.localizedDescription)")
                } else {
                    completion(granted ? "granted" : "denied", "UNUserNotificationCenter.requestAuthorization")
                }
            }
        case "photos":
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
                completion(self.mapPhotos(status), "PHPhotoLibrary.requestAuthorization(for: .readWrite)")
            }
        case "contacts":
            contactStore.requestAccess(for: .contacts) { granted, error in
                if let error = error {
                    completion("denied", "contacts requestAccess 失败：\(error.localizedDescription)")
                } else {
                    completion(granted ? "granted" : "denied", "CNContactStore.requestAccess")
                }
            }
        case "calendar":
            requestEventKit(.event, completion: completion)
        case "reminders":
            requestEventKit(.reminder, completion: completion)
        case "bluetooth":
            // 实例化 CBCentralManager 会触发系统蓝牙授权框；iOS 不提供 completion，只能等一小会儿再读状态
            ensureMain {
                _ = BluetoothPermissionRequester.shared
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    completion(self.mapBluetooth(CBManager.authorization), "CBManager.authorization（授权框已弹出）")
                }
            }
        case "motion":
            ensureMain {
                let manager = CMMotionActivityManager()
                manager.startActivityUpdates(to: .main) { _ in }
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    manager.stopActivityUpdates()
                    completion(self.mapMotion(CMMotionActivityManager.authorizationStatus()),
                               "CMMotionActivityManager.startActivityUpdates（授权框已弹出）")
                }
            }
        case "tracking":
            if #available(iOS 14, *) {
                ATTrackingManager.requestTrackingAuthorization { status in
                    completion(self.mapTracking(status), "ATTrackingManager.requestTrackingAuthorization")
                }
            } else {
                completion("unsupported", "需要 iOS 14+")
            }
        case "speech":
            SFSpeechRecognizer.requestAuthorization { status in
                completion(self.mapSpeech(status), "SFSpeechRecognizer.requestAuthorization")
            }
        default:
            let (state, detail) = stateOf(id)
            completion(state, detail)
        }
    }

    private func requestEventKit(_ entity: EKEntityType, completion: @escaping (String, String) -> Void) {
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

    // MARK: - getSupported

    @objc public func getSupported(_ call: CAPPluginCall) {
        let items: [[String: Any]] = [
            info("camera", true, ["Info.plist: NSCameraUsageDescription"], nil),
            info("microphone", true, ["Info.plist: NSMicrophoneUsageDescription"], nil),
            info("location", true, [
                "Info.plist: NSLocationWhenInUseUsageDescription",
                "（后台定位还需要 NSLocationAlwaysAndWhenInUseUsageDescription + UIBackgroundModes: location）"
            ], "locationCoarse 与 location 共用同一个 iOS 权限（精度由用户在设置里选择）"),
            info("locationCoarse", true, ["Info.plist: NSLocationWhenInUseUsageDescription"],
                 "iOS 14+ 用户可以选择「精确」或「大致位置」，可用 CLLocationManager.accuracyAuthorization 读取"),
            info("notifications", true, ["无需 Info.plist"], nil),
            info("photos", true, ["Info.plist: NSPhotoLibraryUsageDescription"],
                 "iOS 14+ 会返回 limited（用户只选中了部分照片）"),
            info("contacts", true, ["Info.plist: NSContactsUsageDescription"], nil),
            info("calendar", true, ["Info.plist: NSCalendarsUsageDescription"], nil),
            info("reminders", true, ["Info.plist: NSRemindersUsageDescription"], nil),
            info("bluetooth", true, ["Info.plist: NSBluetoothAlwaysUsageDescription"], nil),
            info("motion", true, ["Info.plist: NSMotionUsageDescription"], nil),
            info("tracking", true, ["Info.plist: NSUserTrackingUsageDescription"], "iOS 14+ ATT（App 跟踪透明度）"),
            info("speech", true, ["Info.plist: NSSpeechRecognitionUsageDescription", "Info.plist: NSMicrophoneUsageDescription"], nil),
            info("storage", false, [], "iOS 使用沙盒文件系统，没有「存储权限」概念"),
            info("activityRecognition", false, [], "iOS 没有对应的运行时权限；运动数据用 motion"),
            info("bodySensors", false, [], "iOS 没有对应的运行时权限")
        ]
        call.resolve(["platform": "ios", "permissions": items])
    }

    private func info(_ id: String, _ supported: Bool, _ requires: [String], _ note: String?) -> [String: Any] {
        var item: [String: Any] = ["id": id, "supported": supported, "requires": requires]
        if let note = note { item["note"] = note }
        return item
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

    // MARK: - 状态映射

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

    private func describe(_ status: AVAuthorizationStatus, _ source: String, _ id: String) -> (String, String) {
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
