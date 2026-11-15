import Foundation
import Capacitor


@objc(MyPlugin)
public class MyPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "MyPlugin"
    public let jsName = "MyPlugin"

    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "hello", returnType: CAPPluginReturnPromise)
    ]

    @objc public func hello(_ call: CAPPluginCall) {
        let name = call.getString("name", "--")
        let res = "Hello " + name + " from iosPlugin!"

        call.resolve([
            "message": res
        ])
    }
}
