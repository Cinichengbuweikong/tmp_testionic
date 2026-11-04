import Foundation
import UIKit
import Capacitor

/**
 * Rotation 插件（iOS 侧，jsName = "Rotation"）。
 *
 * 能力：
 * 1. 监听旋转：UIDevice.orientationDidChangeNotification（iOS 15+ 全版本可用，
 *    不用 iOS 26 已 obsoleted 的 windowScene.interfaceOrientation）；
 * 2. 方向锁定：视频「全屏播放」这类场景需要主动横屏——通过修改
 *    CAPBridgeViewController.supportedOrientations（public 可写）+ 请求几何更新实现：
 *    - iOS 16+：setNeedsUpdateOfSupportedInterfaceOrientations() + requestGeometryUpdate()
 *    - iOS 15 ：UIDevice KVC 设方向 + attemptRotationToDeviceOrientation()（旧系统兼容路径）
 *    恢复自动时把 supportedOrientations 还原成进入前记录的值。
 *
 * 方向/角度语义与 rotationChanged 事件保持不变；锁定/解锁只影响系统是否允许转屏。
 */
@objc(RotationPlugin)
public class RotationPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "RotationPlugin"
    public let jsName = "Rotation"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getRotation", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "lock", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "unlock", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLock", returnType: CAPPluginReturnPromise)
    ]

    private var lastOrientation = ""
    private var lastDegrees = -1
    private var observing = false

    /** 当前方向锁定：landscape / portrait / auto。 */
    private var lockedOrientation = "auto"

    /** 进入锁定前 CAPBridgeViewController 支持的原始方向（用于 unlock 还原）。 */
    private var originalSupportedOrientations: [Int]? = nil

    // ---------------------------------------------------------------- 生命周期

    override public func load() {
        super.load()
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(orientationDidChange),
            name: UIDevice.orientationDidChangeNotification,
            object: nil
        )
        observing = true

        let (orientation, degrees) = currentState()
        lastOrientation = orientation
        lastDegrees = degrees
    }

    deinit {
        if observing {
            UIDevice.current.endGeneratingDeviceOrientationNotifications()
        }
        NotificationCenter.default.removeObserver(self)
    }

    // ---------------------------------------------------------------- API

    @objc func getRotation(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let (orientation, degrees) = self.currentState()
            call.resolve(self.stateDict(orientation: orientation, degrees: degrees))
        }
    }

    /// 锁定方向：landscape / portrait / auto
    @objc func lock(_ call: CAPPluginCall) {
        let requested = call.getString("orientation", "landscape")
        DispatchQueue.main.async {
            guard let vc = self.bridge?.viewController as? CAPBridgeViewController else {
                call.reject("viewController unavailable")
                return
            }
            if self.originalSupportedOrientations == nil {
                self.originalSupportedOrientations = vc.supportedOrientations
            }
            switch requested {
            case "landscape":
                vc.supportedOrientations = [
                    UIInterfaceOrientation.landscapeLeft.rawValue,
                    UIInterfaceOrientation.landscapeRight.rawValue
                ]
                self.lockedOrientation = "landscape"
                self.requestOrientationChange(mask: .landscape, preferred: .landscapeRight)
            case "portrait":
                vc.supportedOrientations = [
                    UIInterfaceOrientation.portrait.rawValue,
                    UIInterfaceOrientation.portraitUpsideDown.rawValue
                ]
                self.lockedOrientation = "portrait"
                self.requestOrientationChange(mask: .portrait, preferred: .portrait)
            case "auto":
                self.restoreAutoOrientation(vc: vc)
            default:
                call.reject("orientation must be one of: landscape, portrait, auto")
                return
            }
            call.resolve(self.lockStateDict())
        }
    }

    /// 解除锁定，恢复系统自动旋转。
    @objc func unlock(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            guard let vc = self.bridge?.viewController as? CAPBridgeViewController else {
                call.reject("viewController unavailable")
                return
            }
            self.restoreAutoOrientation(vc: vc)
            call.resolve(self.lockStateDict())
        }
    }

    /// 读取当前锁定状态。
    @objc func getLock(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            call.resolve(self.lockStateDict())
        }
    }

    private func restoreAutoOrientation(vc: CAPBridgeViewController) {
        if let original = originalSupportedOrientations {
            vc.supportedOrientations = original
        }
        lockedOrientation = "auto"
        if #available(iOS 16.0, *) {
            vc.setNeedsUpdateOfSupportedInterfaceOrientations()
        } else {
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }

    private func lockStateDict() -> [String: Any] {
        [
            "orientation": lockedOrientation,
            "locked": lockedOrientation != "auto"
        ]
    }

    /// 请求界面旋转到目标方向（iOS 16+ 官方接口；iOS 15 用 KVC 兼容路径）。
    private func requestOrientationChange(mask: UIInterfaceOrientationMask, preferred: UIInterfaceOrientation) {
        guard let vc = self.bridge?.viewController as? CAPBridgeViewController else {
            return
        }
        if #available(iOS 16.0, *) {
            vc.setNeedsUpdateOfSupportedInterfaceOrientations()
            vc.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { error in
                CAPLog.print("[Rotation] requestGeometryUpdate failed: \(error)")
            }
        } else {
            UIDevice.current.setValue(preferred.rawValue, forKey: "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }

    // ---------------------------------------------------------------- 内部

    @objc private func orientationDidChange() {
        DispatchQueue.main.async {
            let (orientation, degrees) = self.currentState()
            if orientation == self.lastOrientation && degrees == self.lastDegrees {
                return
            }
            self.lastOrientation = orientation
            self.lastDegrees = degrees
            self.notifyListeners(
                "rotationChanged",
                data: self.stateDict(orientation: orientation, degrees: degrees)
            )
        }
    }

    private func stateDict(orientation: String, degrees: Int) -> [String: Any] {
        [
            "orientation": orientation,
            "rotationDegrees": degrees,
            "isPortrait": orientation == "portrait",
            "isLandscape": orientation == "landscape"
        ]
    }

    /// 方向：WebView 宽高比（实际渲染方向）；角度：UIDevice 物理方向映射。
    private func currentState() -> (String, Int) {
        let bounds = self.bridge?.webView?.bounds ?? UIScreen.main.bounds
        let isLandscape = bounds.width > bounds.height
        let orientation = isLandscape ? "landscape" : "portrait"

        let degrees: Int
        switch UIDevice.current.orientation {
        case .portrait:
            degrees = 0
        case .portraitUpsideDown:
            degrees = 180
        case .landscapeLeft:
            degrees = 270
        case .landscapeRight:
            degrees = 90
        default:
            // faceUp / faceDown / unknown：UIDevice 方向不可靠，按视图比例兜底
            degrees = isLandscape ? 90 : 0
        }
        return (orientation, degrees)
    }
}
