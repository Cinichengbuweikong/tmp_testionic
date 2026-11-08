import Foundation
import UIKit
import Capacitor

/**
 * ForegroundState 插件（iOS 侧，jsName = "ForegroundState"）。
 *
 * 通过 UIApplication 通知判断前后台：
 *  - `didBecomeActiveNotification`        → active
 *  - `willResignActiveNotification`       → inactive（来电/控制中心/App 切换器/锁屏等）
 *  - `didEnterBackgroundNotification`     → background
 *  - `willEnterForegroundNotification`    → inactive（即将回前台，稍后 didBecomeActive）
 *
 * 事件：`stateChanged`，载荷为 ForegroundStateInfo（isActive / state / changedAt /
 * lastBackgroundAt / backgroundDuration）。
 *
 * 注意：iOS 进后台后 socket/定时器会被系统挂起，只有约 30 秒的 beginBackgroundTask 窗口。
 */
@objc(ForegroundStatePlugin)
public class ForegroundStatePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ForegroundStatePlugin"
    public let jsName = "ForegroundState"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getState", returnType: CAPPluginReturnPromise)
    ]

    private var state = "active"
    private var changedAt = Int64(Date().timeIntervalSince1970 * 1000)
    private var lastBackgroundAt: Int64 = 0
    private var backgroundDuration: Int64 = 0

    // ---------------------------------------------------------------- 生命周期

    override public func load() {
        super.load()
        let center = NotificationCenter.default
        center.addObserver(
            self,
            selector: #selector(onBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(onResignActive),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(onEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        center.addObserver(
            self,
            selector: #selector(onEnterForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // ---------------------------------------------------------------- API

    @objc func getState(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            call.resolve(self.stateDict())
        }
    }

    // ---------------------------------------------------------------- 通知

    @objc private func onBecomeActive() {
        transition(to: "active")
    }

    @objc private func onResignActive() {
        transition(to: "inactive")
    }

    @objc private func onEnterBackground() {
        transition(to: "background")
    }

    @objc private func onEnterForeground() {
        transition(to: "inactive")
    }

    // ---------------------------------------------------------------- 内部

    private func transition(to newState: String) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if newState == state {
            return
        }
        if newState == "background" {
            lastBackgroundAt = now
            backgroundDuration = 0
        }
        if state == "background", newState == "active" {
            backgroundDuration = lastBackgroundAt > 0 ? now - lastBackgroundAt : 0
        }
        state = newState
        changedAt = now
        notifyListeners("stateChanged", data: stateDict())
    }

    private func stateDict() -> [String: Any] {
        return [
            "isActive": state == "active",
            "state": state,
            "changedAt": changedAt,
            "lastBackgroundAt": lastBackgroundAt,
            "backgroundDuration": backgroundDuration
        ]
    }
}
