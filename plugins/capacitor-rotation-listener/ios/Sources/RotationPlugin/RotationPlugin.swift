import Foundation
import UIKit
import Capacitor

/**
 * Rotation 插件（iOS 侧，jsName = "Rotation"）。
 *
 * 实现要点：
 * 1. 监听 UIDevice.orientationDidChangeNotification（iOS 15+ 全版本可用，
 *    不用 iOS 26 已 obsoleted 的 windowScene.interfaceOrientation）；
 * 2. `orientation` 用 WebView 宽高比判断（= 实际渲染方向，faceUp/faceDown 时也可靠），
 *    `rotationDegrees` 用 UIDevice 物理方向映射（0/90/180/270）；
 * 3. 方向或角度真的变化才派发，避免重复事件；
 * 4. 监听/解除监听由 Capacitor 桥的 JS 侧 addListener/removeListener 处理。
 */
@objc(RotationPlugin)
public class RotationPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "RotationPlugin"
    public let jsName = "Rotation"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getRotation", returnType: CAPPluginReturnPromise)
    ]

    private var lastOrientation = ""
    private var lastDegrees = -1
    private var observing = false

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
