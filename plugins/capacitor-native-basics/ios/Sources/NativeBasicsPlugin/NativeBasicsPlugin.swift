import Foundation
import UIKit
import Capacitor

@objc(NativeBasicsPlugin)
public class NativeBasicsPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "NativeBasicsPlugin"
    public let jsName = "NativeBasics"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "echo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "showToast", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDeviceInfo", returnType: CAPPluginReturnPromise)
    ]

    @objc func echo(_ call: CAPPluginCall) {
        guard let value = call.getString("value") else {
            call.reject("value is required")
            return
        }
        call.resolve(["value": value])
    }

    @objc func showToast(_ call: CAPPluginCall) {
        guard let text = call.getString("text") else {
            call.reject("text is required")
            return
        }

        let duration = call.getString("duration") ?? "short"
        let seconds: TimeInterval = duration == "long" ? 3.5 : 2.0

        DispatchQueue.main.async { [weak self] in
            guard let hostView = self?.bridge?.viewController?.view else {
                call.reject("No view available to show a toast")
                return
            }
            self?.presentToast(text: text, seconds: seconds, in: hostView)
            call.resolve()
        }
    }

    @objc func getDeviceInfo(_ call: CAPPluginCall) {
        let device = UIDevice.current
        // iOS 没有 Android 的 API Level 概念，故不返回 sdkInt（TS 类型里它是可选的）
        let result: [String: Any] = [
            "manufacturer": "Apple",
            "model": device.model,
            "osVersion": device.systemVersion,
            "platform": "ios",
            "isEmulator": isEmulator()
        ]
        call.resolve(result)
    }

    private func isEmulator() -> Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    /// iOS 没有系统级 Toast，这里用一个短暂显示的浮层模拟。
    private func presentToast(text: String, seconds: TimeInterval, in hostView: UIView) {
        let container = UIView()
        container.backgroundColor = UIColor.black.withAlphaComponent(0.82)
        container.layer.cornerRadius = 14
        container.translatesAutoresizingMaskIntoConstraints = false
        container.alpha = 0

        let label = UILabel()
        label.text = text
        label.textColor = .white
        label.font = UIFont.systemFont(ofSize: 15)
        label.numberOfLines = 0
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false

        container.addSubview(label)
        hostView.addSubview(container)

        NSLayoutConstraint.activate([
            label.topAnchor.constraint(equalTo: container.topAnchor, constant: 12),
            label.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -12),
            label.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 18),
            label.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -18),
            container.centerXAnchor.constraint(equalTo: hostView.centerXAnchor),
            container.bottomAnchor.constraint(
                equalTo: hostView.safeAreaLayoutGuide.bottomAnchor,
                constant: -72
            ),
            container.widthAnchor.constraint(lessThanOrEqualTo: hostView.widthAnchor, multiplier: 0.8)
        ])

        UIView.animate(withDuration: 0.25, animations: {
            container.alpha = 1
        }, completion: { _ in
            UIView.animate(withDuration: 0.25, delay: seconds, options: [.curveEaseInOut], animations: {
                container.alpha = 0
            }, completion: { _ in
                container.removeFromSuperview()
            })
        })
    }
}
