import Foundation
import UIKit
import Capacitor

/**
 * 状态栏插件（iOS 侧实现）。
 *
 * ============================ 一、iOS 状态栏没有"背景色" ============================
 *
 * 与 Android 不同，iOS 的状态栏从来只是一层透明的系统 UI，浮在 App 内容之上，
 * 系统根本没有提供"设置状态栏背景色"的 API。所以这里的做法是**自己画**：
 * 在视图控制器根视图顶部铺一层 UIView，高度等于安全区顶部（刘海/灵动岛高度），
 * 颜色由 JS 传入。约束写成 `bottomAnchor == view.safeAreaLayoutGuide.topAnchor`，
 * 这样旋转、横屏、不同机型都会自动适配，不需要自己算高度。
 *
 * 注意：色块会盖住页面顶部那一条，Web 层需要自己让出安全区
 * （`padding-top: env(safe-area-inset-top)` 之类）。
 *
 * ============================ 二、图标亮暗 ============================
 *
 * iOS 侧通过 CAPBridgeViewController.setStatusBarStyle(_:) 修改：
 *   light   -> .lightContent （白色图标，适合深色底）
 *   dark    -> .darkContent  （深色图标，适合浅色底，iOS 13+）
 *   default -> .default      （跟随系统）
 * 调用后 Capacitor 内部会触发 setNeedsStatusBarAppearanceUpdate() 并带 0.2s 动画。
 *
 * 与 Android 的差异：iOS 不会像 MIUI 12（Android 10）那样按背景色强制覆盖图标颜色，
 * 这里设置的样式就是最终结果。（Android 侧 MIUI 12 的限制见 Java 类头部注释第三节。）
 *
 * ============================ 三、线程 ============================
 *
 * 与 Android 一样，Capacitor 的插件方法不在主线程，所有 UIKit 操作都切到主线程执行。
 */
@objc(NativeStatusBarPlugin)
public class NativeStatusBarPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "NativeStatusBarPlugin"
    public let jsName = "NativeStatusBar"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "setBackgroundColor", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setStyle", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getInfo", returnType: CAPPluginReturnPromise)
    ]

    private var backgroundView: UIView?
    private var currentColor: UIColor?
    private var currentStyle: String = "default"

    // MARK: - Plugin methods

    @objc func setBackgroundColor(_ call: CAPPluginCall) {
        guard let value = call.getString("color") else {
            call.reject("color is required")
            return
        }
        guard let color = Self.parseColor(value) else {
            call.reject("Invalid color: \(value) (expected #RGB, #RRGGBB, #AARRGGBB or transparent)")
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self else {
                call.reject("Plugin deallocated")
                return
            }
            self.currentColor = color
            self.applyBackgroundColor(color)
            call.resolve([
                "color": Self.toHex(color),
                "height": self.statusBarHeight()
            ])
        }
    }

    @objc func setStyle(_ call: CAPPluginCall) {
        let value = (call.getString("style") ?? "default").lowercased()
        guard ["light", "dark", "default"].contains(value) else {
            call.reject("style must be one of: light, dark, default")
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self else {
                call.reject("Plugin deallocated")
                return
            }
            self.currentStyle = value

            guard let viewController = self.bridge?.viewController as? CAPBridgeViewController else {
                call.reject("Bridge view controller unavailable")
                return
            }

            switch value {
            case "light":
                viewController.setStatusBarStyle(.lightContent)
            case "dark":
                viewController.setStatusBarStyle(.darkContent)
            default:
                viewController.setStatusBarStyle(.default)
            }
            call.resolve()
        }
    }

    @objc func getInfo(_ call: CAPPluginCall) {
        var result: [String: Any] = [
            "style": currentStyle,
            "height": statusBarHeight(),
            // iOS 侧永远是自己画的那层
            "usingOverlay": backgroundView != nil
        ]
        if let color = currentColor {
            result["color"] = Self.toHex(color)
        } else {
            result["color"] = NSNull()
        }
        call.resolve(result)
    }

    // MARK: - Background layer

    private func applyBackgroundColor(_ color: UIColor) {
        guard let host = bridge?.viewController?.view else {
            return
        }

        var alpha: CGFloat = 0
        color.getRed(nil, green: nil, blue: nil, alpha: &alpha)

        if alpha == 0 {
            backgroundView?.removeFromSuperview()
            backgroundView = nil
            return
        }

        let view: UIView
        if let existing = backgroundView, existing.superview === host {
            view = existing
        } else {
            backgroundView?.removeFromSuperview()

            let created = UIView()
            created.translatesAutoresizingMaskIntoConstraints = false
            created.isUserInteractionEnabled = false
            created.accessibilityIdentifier = "native-status-bar-overlay"
            host.addSubview(created)

            NSLayoutConstraint.activate([
                created.topAnchor.constraint(equalTo: host.topAnchor),
                created.leadingAnchor.constraint(equalTo: host.leadingAnchor),
                created.trailingAnchor.constraint(equalTo: host.trailingAnchor),
                // 底边对齐安全区顶部：高度正好等于状态栏（含刘海）高度，旋转自动适配
                created.bottomAnchor.constraint(equalTo: host.safeAreaLayoutGuide.topAnchor)
            ])

            backgroundView = created
            view = created
        }

        view.backgroundColor = color
        host.bringSubviewToFront(view)
    }

    private func statusBarHeight() -> CGFloat {
        guard let viewController = bridge?.viewController else {
            return 0
        }
        let safeAreaTop = viewController.view.safeAreaInsets.top
        let frameHeight = viewController.view.window?.windowScene?.statusBarManager?.statusBarFrame.height ?? 0
        return max(safeAreaTop, frameHeight)
    }

    // MARK: - Color helpers

    private static func parseColor(_ value: String) -> UIColor? {
        let input = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if input == "transparent" {
            return .clear
        }
        guard input.hasPrefix("#") else {
            return nil
        }

        var hex = String(input.dropFirst())
        if hex.count == 3 || hex.count == 4 {
            hex = hex.map { "\($0)\($0)" }.joined()
        }
        guard (hex.count == 6 || hex.count == 8), let number = UInt64(hex, radix: 16) else {
            return nil
        }

        let a, r, g, b: UInt64
        if hex.count == 8 {
            // #AARRGGBB，与 Android 侧保持一致
            a = (number >> 24) & 0xFF
            r = (number >> 16) & 0xFF
            g = (number >> 8) & 0xFF
            b = number & 0xFF
        } else {
            a = 0xFF
            r = (number >> 16) & 0xFF
            g = (number >> 8) & 0xFF
            b = number & 0xFF
        }

        return UIColor(
            red: CGFloat(r) / 255.0,
            green: CGFloat(g) / 255.0,
            blue: CGFloat(b) / 255.0,
            alpha: CGFloat(a) / 255.0
        )
    }

    private static func toHex(_ color: UIColor) -> String {
        var r: CGFloat = 0
        var g: CGFloat = 0
        var b: CGFloat = 0
        var a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)

        return String(
            format: "#%02X%02X%02X%02X",
            Int((a * 255).rounded()),
            Int((r * 255).rounded()),
            Int((g * 255).rounded()),
            Int((b * 255).rounded())
        )
    }
}
