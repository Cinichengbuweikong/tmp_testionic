import AVFoundation
import Capacitor
import CoreLocation
import Foundation
import UIKit

/**
 * 后台保活（iOS 侧）。
 *
 * iOS **没有** Android 那种「常驻通知 + 前台服务」的等价物，也没有任何 API 能保证任意 App
 * 在后台持续运行。本插件提供三种"尽力而为"的方式，每种都有明确的适用条件与副作用：
 *
 * 1. `audio`  —— 后台播放静音音频（`AVAudioSession.playback` + 循环静音 WAV）。
 *    这是唯一能让进程**持续**运行的方案，需要宿主 Info.plist 里有
 *    `UIBackgroundModes = [audio]`。非音频类 App 有被 App Store 拒审的风险。
 * 2. `location` —— 持续定位（`allowsBackgroundLocationUpdates`）。需要
 *    `UIBackgroundModes = [location]` + 定位权限，只适合导航/运动类 App。
 * 3. `backgroundTask` —— `beginBackgroundTask` 续期，只能多争取几十秒~几分钟，
 *    之后系统仍然会挂起 App（`expirationHandler` 会被调用）。
 *
 * Web 端没有后台执行保证。README 有完整能力矩阵与商店政策说明。
 */
@objc(BackgroundKeepalivePlugin)
public class BackgroundKeepalivePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "BackgroundKeepalivePlugin"
    public let jsName = "BackgroundKeepalive"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isRunning", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "updateNotification", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getCapabilities", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isBatteryOptimizationIgnored", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestBatteryOptimizationExemption", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openBatteryOptimizationSettings", returnType: CAPPluginReturnPromise)
    ]

    private enum ActiveMode: String {
        case none
        case audio
        case location
        case backgroundTask = "background-task"
    }

    private var activeMode: ActiveMode = .none
    private var audioPlayer: AVAudioPlayer?
    private var locationManager: CLLocationManager?
    private var bgTask: UIBackgroundTaskIdentifier = .invalid
    private var audioVolume: Float = 1.0
    private var distanceFilter: Double = 10
    private var lastNote: String?
    /** 后台任务到期事件的缓冲：挂起期间 JS 执行不了，回到前台时补发一次。 */
    private var pendingExpirationEvent: [String: Any]?
    private var foregroundObserver: NSObjectProtocol?
    private var backgroundObserver: NSObjectProtocol?

    // MARK: - 生命周期

    override public func load() {
        let center = NotificationCenter.default
        foregroundObserver = center.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            // 挂起期间 expirationHandler 里发的事件 JS 执行不到，这里补发
            self?.flushPendingExpiration()
        }
        backgroundObserver = center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self = self, self.activeMode == .backgroundTask else { return }
            // beginBackgroundTask 的计时是从「进入后台」开始的，这里重新申请一次
            self.beginBackgroundTask()
            self.notifyStatus()
        }
    }

    deinit {
        if let observer = foregroundObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        if let observer = backgroundObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        stopEverything()
    }

    // MARK: - API

    @objc public func start(_ call: CAPPluginCall) {
        let requested = call.getString("mode", "auto") ?? "auto"
        audioVolume = call.getFloat("audioVolume", 1.0)
        distanceFilter = call.getDouble("distanceFilter", 10)

        switch requested {
        case "auto":
            // iOS 上的默认策略：backgroundTask（安全、无商店风险）。
            // 想长期后台运行必须**显式**传 audio / location，并自己承担审核/耗电后果。
            startBackgroundTask(call)
        case "audio":
            startAudio(call)
        case "location":
            startLocation(call)
        case "backgroundTask", "background-task":
            startBackgroundTask(call)
        case "specialUse", "mediaPlayback", "dataSync":
            // 这些是 Android 的模式名；iOS 上按默认策略处理，方便跨平台代码复用
            startBackgroundTask(call)
        default:
            call.reject("UNSUPPORTED_MODE: iOS 只支持 auto / audio / location / backgroundTask", "UNSUPPORTED_MODE")
        }
    }

    @objc public func stop(_ call: CAPPluginCall) {
        stopEverything()
        notifyStatus()
        call.resolve(statusObject())
    }

    @objc public func isRunning(_ call: CAPPluginCall) {
        call.resolve(statusObject())
    }

    @objc public func updateNotification(_ call: CAPPluginCall) {
        // iOS 没有常驻通知，能做的只有给 iOS 16+ 的 Live Activity（需要 Widget Extension，超出插件范围）
        lastNote = "iOS 没有常驻通知；updateNotification() 是空操作。"
        call.resolve(statusObject())
    }

    @objc public func getCapabilities(_ call: CAPPluginCall) {
        var modes: [[String: Any]] = []
        modes.append([
            "mode": "backgroundTask",
            "supported": true,
            "requires": [],
            "effect": "默认模式：每次 beginBackgroundTask 只能延长约 30 秒，可续期但系统仍会挂起 App；"
                + "到期时会派发 keepaliveExpired 事件（挂起期间产生的会在回到前台时补发）",
            "recommended": true
        ])
        modes.append([
            "mode": "audio",
            "supported": true,
            "requires": [
                "Info.plist: UIBackgroundModes 包含 audio",
                "（可选）AVAudioSession 允许与其他音频共存"
            ],
            "effect": "后台持续运行（唯一能长期保活的方式）；非音频类 App 有 App Store 拒审风险",
            "recommended": false
        ])
        modes.append([
            "mode": "location",
            "supported": true,
            "requires": [
                "Info.plist: UIBackgroundModes 包含 location",
                "Info.plist: NSLocationAlwaysAndWhenInUseUsageDescription",
                "用户授予「始终允许」定位权限"
            ],
            "effect": "靠持续定位保持运行；只适合导航/运动类 App，耗电明显",
            "recommended": false
        ])
        call.resolve([
            "platform": "ios",
            "continuous": hasBackgroundMode("audio") || hasBackgroundMode("location"),
            "note": "iOS 没有常驻通知/前台服务；任意 App 的永久后台运行在系统层面不存在。",
            "modes": modes
        ])
    }

    @objc public func isBatteryOptimizationIgnored(_ call: CAPPluginCall) {
        call.resolve(["applicable": false, "ignored": true])
    }

    @objc public func requestBatteryOptimizationExemption(_ call: CAPPluginCall) {
        call.resolve(["applicable": false, "ignored": true, "requested": false])
    }

    @objc public func openBatteryOptimizationSettings(_ call: CAPPluginCall) {
        call.resolve(["opened": false])
    }

    // MARK: - 模式实现

    private func startAudio(_ call: CAPPluginCall) {
        guard hasBackgroundMode("audio") else {
            call.reject(
                "BACKGROUND_MODE_MISSING: 需要在 Info.plist 的 UIBackgroundModes 里加入 audio",
                "BACKGROUND_MODE_MISSING"
            )
            return
        }
        DispatchQueue.main.async {
            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
                try session.setActive(true)

                let data = self.makeSilentWav()
                let player = try AVAudioPlayer(data: data)
                player.numberOfLoops = -1
                player.volume = self.audioVolume
                player.prepareToPlay()
                guard player.play() else {
                    throw NSError(domain: "BackgroundKeepalive", code: 1,
                                  userInfo: [NSLocalizedDescriptionKey: "AVAudioPlayer.play() 返回 false"])
                }
                self.audioPlayer?.stop()
                self.audioPlayer = player
                self.stopLocation()
                self.endBackgroundTask()
                self.activeMode = .audio
                self.observeAudioInterruptions()
                self.lastNote = "正在后台播放静音音频保持运行；App Store 审核可能要求这是音频类 App。"
                self.notifyStatus()
                call.resolve(self.statusObject())
            } catch {
                call.reject("AUDIO_START_FAILED: \(error.localizedDescription)", "AUDIO_START_FAILED")
            }
        }
    }

    private func startLocation(_ call: CAPPluginCall) {
        guard hasBackgroundMode("location") else {
            call.reject(
                "BACKGROUND_MODE_MISSING: 需要在 Info.plist 的 UIBackgroundModes 里加入 location",
                "BACKGROUND_MODE_MISSING"
            )
            return
        }
        DispatchQueue.main.async {
            let manager = CLLocationManager()
            manager.delegate = self
            manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
            manager.distanceFilter = self.distanceFilter
            manager.allowsBackgroundLocationUpdates = true
            manager.pausesLocationUpdatesAutomatically = false
            let status = manager.authorizationStatus
            if status == .notDetermined {
                manager.requestAlwaysAuthorization()
            }
            manager.startUpdatingLocation()
            self.locationManager = manager
            self.stopAudio()
            self.endBackgroundTask()
            self.activeMode = .location
            self.lastNote = "正在持续定位保持运行；需要「始终允许」定位权限。"
            self.notifyStatus()
            call.resolve(self.statusObject())
        }
    }

    private func startBackgroundTask(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.stopAudio()
            self.stopLocation()
            // 先设模式再申请：`beginBackgroundTask` 失败时会把 activeMode 复位为 .none，
            // 反过来写就会出现"申请失败但状态显示 running:true"的假象（旧实现就是这个 bug）。
            self.activeMode = .backgroundTask
            self.beginBackgroundTask()
            if self.activeMode == .backgroundTask {
                self.lastNote = "backgroundTask 只能把挂起时间往后推约 30 秒（可续期，但不保证），"
                    + "不适合需要长期在线的场景；请改用推送或后台 URLSession。"
            }
            self.notifyStatus()
            call.resolve(self.statusObject())
        }
    }

    private func stopEverything() {
        stopAudio()
        stopLocation()
        endBackgroundTask()
        activeMode = .none
        lastNote = nil
        pendingExpirationEvent = nil
    }

    private func stopAudio() {
        if let player = audioPlayer {
            player.stop()
            audioPlayer = nil
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        }
    }

    private func stopLocation() {
        locationManager?.stopUpdatingLocation()
        locationManager?.delegate = nil
        locationManager = nil
    }

    // MARK: - 后台任务续期 / 到期事件

    private func beginBackgroundTask() {
        endBackgroundTask()
        bgTask = UIApplication.shared.beginBackgroundTask(withName: "capacitor-background-keepalive") { [weak self] in
            guard let self = self, self.activeMode == .backgroundTask else { return }
            self.handleBackgroundTaskExpiration()
        }
        if bgTask == .invalid {
            activeMode = .none
            lastNote = "beginBackgroundTask 申请失败：App 可能已经在后台，或系统拒绝了请求。"
        }
    }

    /**
     * `expirationHandler` 触发时（后台时间用完、系统即将挂起 App）：
     *
     * 1. **尽力**立刻 `notifyListeners("keepaliveExpired")`：`.invalid` 之外的调用会被排进
     *    WKWebView 的 JS 队列，但此刻 App 即将被挂起，JS **通常不会马上执行**；
     * 2. 把事件**缓存**下来，等 `didBecomeActive` 时补发一次（`deliveredAfterResume: true`），
     *    这样用户回到前台后 JS 一定能收到，而不是"发了但永远看不到"；
     * 3. 再申请一次后台任务（可续期，但 iOS 不保证）。
     *
     * 注意：`expirationHandler` 可能在任意线程，UI/桥操作统一回主线程。
     */
    private func handleBackgroundTaskExpiration() {
        let now = Date().timeIntervalSince1970 * 1000
        var payload: [String: Any] = [
            "expiredAt": now,
            "deliveredAt": NSNull(),
            "deliveredAfterResume": false,
            "mode": "background-task",
            "backgroundTimeRemaining": UIApplication.shared.backgroundTimeRemaining,
            "reason": "background-task-expired",
            "note": "后台任务到期：iOS 即将挂起 App。这条事件可能要到 App 回到前台才真正执行。"
        ]
        DispatchQueue.main.async {
            payload["backgroundTimeRemaining"] = 0
            self.pendingExpirationEvent = payload
            // ① 尽力立刻派发（多半会被挂起推迟）
            self.notifyListeners("keepaliveExpired", data: payload)
            // ② 续期 + 更新状态
            self.lastNote = "后台任务已到期，已重新申请（iOS 不保证长期有效）；"
                + "keepaliveExpired 事件已记录，回到前台时会补发一次。"
            self.beginBackgroundTask()
            self.notifyStatus()
        }
    }

    /** 回到前台时把挂起期间产生的到期事件补发一次（每个事件只补一次）。 */
    private func flushPendingExpiration() {
        guard var payload = pendingExpirationEvent else { return }
        pendingExpirationEvent = nil
        payload["deliveredAfterResume"] = true
        payload["deliveredAt"] = Date().timeIntervalSince1970 * 1000
        payload["note"] = "这是挂起期间产生的 keepaliveExpired 事件，App 回到前台后才真正执行到 JS。"
        notifyListeners("keepaliveExpired", data: payload)
    }

    private func endBackgroundTask() {
        if bgTask != .invalid {
            UIApplication.shared.endBackgroundTask(bgTask)
            bgTask = .invalid
        }
    }

    // MARK: - 音频中断

    /// 来电/Siri/其他 App 抢占音频会话时，iOS 会中断播放；中断结束后（shouldResume）自动恢复。
    private func observeAudioInterruptions() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
    }

    @objc private func handleAudioInterruption(_ notification: Notification) {
        guard activeMode == .audio,
              let info = notification.userInfo,
              let raw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            lastNote = "音频会话被中断（来电/其他 App），保活暂时失效。"
            notifyStatus()
        case .ended:
            let options = (info[AVAudioSessionInterruptionOptionKey] as? UInt).map {
                AVAudioSession.InterruptionOptions(rawValue: $0)
            }
            if options?.contains(.shouldResume) ?? false {
                try? AVAudioSession.sharedInstance().setActive(true)
                if audioPlayer?.play() == true {
                    lastNote = "音频中断结束，已恢复静音播放。"
                    notifyStatus()
                    return
                }
            }
            lastNote = "音频中断结束，但无法自动恢复播放；请重新 start({ mode: 'audio' })。"
            notifyStatus()
        @unknown default:
            break
        }
    }

    // MARK: - 工具

    private func hasBackgroundMode(_ mode: String) -> Bool {
        guard let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] else {
            return false
        }
        return modes.contains(mode)
    }

    /// 生成 1 秒 8kHz 单声道 16bit 的**全零** WAV（无声），用于循环播放。
    private func makeSilentWav(seconds: Double = 1.0, sampleRate: Int = 8000) -> Data {
        let sampleCount = Int(Double(sampleRate) * seconds)
        let dataSize = sampleCount * 2
        var data = Data()
        func ascii(_ value: String) { data.append(contentsOf: Array(value.utf8)) }
        func u32(_ value: UInt32) {
            var little = value.littleEndian
            withUnsafeBytes(of: &little) { data.append(contentsOf: $0) }
        }
        func u16(_ value: UInt16) {
            var little = value.littleEndian
            withUnsafeBytes(of: &little) { data.append(contentsOf: $0) }
        }
        ascii("RIFF")
        u32(UInt32(36 + dataSize))
        ascii("WAVE")
        ascii("fmt ")
        u32(16)
        u16(1)                      // PCM
        u16(1)                      // 单声道
        u32(UInt32(sampleRate))
        u32(UInt32(sampleRate * 2)) // byte rate
        u16(2)                      // block align
        u16(16)                     // bits per sample
        ascii("data")
        u32(UInt32(dataSize))
        data.append(Data(count: dataSize))
        return data
    }

    private func statusObject() -> [String: Any] {
        var result: [String: Any] = [
            "running": activeMode != .none,
            "platform": "ios",
            "mode": activeMode.rawValue
        ]
        if activeMode == .backgroundTask {
            let remaining = UIApplication.shared.backgroundTimeRemaining
            result["backgroundTimeRemaining"] = remaining.isFinite ? remaining : -1
        } else {
            result["backgroundTimeRemaining"] = NSNull()
        }
        if let note = lastNote {
            result["note"] = note
        }
        return result
    }

    private func notifyStatus() {
        notifyListeners("keepaliveChanged", data: statusObject())
    }
}

// MARK: - CLLocationManagerDelegate

extension BackgroundKeepalivePlugin: CLLocationManagerDelegate {
    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // 只是为了让 App 保持活跃，不需要处理坐标
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        lastNote = "定位失败：\(error.localizedDescription)（持续定位可能已中断）"
        notifyStatus()
    }

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted {
            lastNote = "定位权限被拒绝，location 模式无法保活。"
            notifyStatus()
        }
    }
}
