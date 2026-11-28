// swift-tools-version: 5.9
import PackageDescription

/**
 * capacitor-linux 的 iOS 侧。
 *
 * 目前只有 Swift 实现（API 面完整、返回 UNSUPPORTED_PLATFORM）。真正的 Linux 用户态
 * 需要解释器级内核（iSH / QEMU TCI），那会引入 C/C++ 代码与二进制产物，届时在这里加
 * target 与 binaryTarget（参考 capacitor-python 的 Package.swift）。
 */
let package = Package(
    name: "CapacitorLinux",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "CapacitorLinux", targets: ["LinuxPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "LinuxPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/LinuxPlugin"
        )
    ]
)
