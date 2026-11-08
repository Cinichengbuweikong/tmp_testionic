// swift-tools-version: 5.9

import PackageDescription

// 包名必须与 Capacitor CLI 由 npm 包名推导出来的名字一致：
// capacitor-webview-to-edge -> CapacitorWebviewToEdge
let package = Package(
    name: "CapacitorWebviewToEdge",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapacitorWebviewToEdge",
            targets: ["WebViewToEdgePlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "WebViewToEdgePlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/WebViewToEdgePlugin")
    ]
)
