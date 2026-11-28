// swift-tools-version: 5.9
import Foundation
import PackageDescription

/**
 * capacitor-linux 的 iOS 侧。
 *
 * 引擎是 **iSH-ARM64**（`everettjf/dsh-ios` 里的 `ish-arm64/`，Asbestos 线程码解释器，
 * AArch64 客户机）。它不在本仓库里：需要 macOS 用
 * `scripts/fetch-ish-ios-runtime.sh` 构建出 `ios/CapISH.xcframework`（脚本还会把
 * `ISHShellExecutor.h` 复制到 `ios/Sources/CapISHBridge/include/`）。
 *
 * 这里根据 framework 是否**存在**决定怎么编：
 * - 存在  → 加 `.binaryTarget(name: "CapISH", ...)`，`CapISHBridge` 依赖它，
 *           桥的 `__has_include("ISHShellExecutor.h")` 成立，走真引擎；
 * - 不存在 → 只编桥的桩实现，插件照常编译，`getCapabilities().supported == false`
 *           并给出原因（iOS CI 靠这条路径保持绿色）。
 *
 * 所以"有没有引擎"是构建期决定，不需要改两份代码，也不需要条件编译宏。
 */
let packageDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let ishFramework = packageDir.appendingPathComponent("ios/CapISH.xcframework")
let hasISH = FileManager.default.fileExists(atPath: ishFramework.path)

var targets: [Target] = [
    // 引擎桥（C 接口）：有 iSH 头文件/库就走真引擎，否则桩
    .target(
        name: "CapISHBridge",
        dependencies: hasISH ? ["CapISH"] : [],
        path: "ios/Sources/CapISHBridge",
        publicHeadersPath: "include"
    ),
    .target(
        name: "LinuxPlugin",
        dependencies: [
            "CapISHBridge",
            .product(name: "Capacitor", package: "capacitor-swift-pm"),
            .product(name: "Cordova", package: "capacitor-swift-pm")
        ],
        path: "ios/Sources/LinuxPlugin"
    )
]
if hasISH {
    targets.insert(.binaryTarget(name: "CapISH", path: "ios/CapISH.xcframework"), at: 0)
}

let package = Package(
    name: "CapacitorLinux",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "CapacitorLinux", targets: ["LinuxPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: targets
)
