// swift-tools-version: 5.9
import Foundation
import PackageDescription

/**
 * capacitor-linux 的 iOS 侧。
 *
 * 引擎是 **iSH-ARM64**（`everettjf/dsh-ios` 里的 `ish-arm64/`，Asbestos 线程码解释器，
 * AArch64 客户机）。它不在本仓库里：需要 macOS 用
 * `scripts/fetch-ish-ios-runtime.sh` 构建出 `ios/CapISH.xcframework`（脚本同时会把
 * `ISHShellExecutor.h` 复制到 `ios/Sources/CapISHBridge/include/`）。
 *
 * "有没有引擎"是**构建期**决定的（见文件末尾的 `linuxTargets()`）：
 * - framework 存在 → 加一个 binaryTarget（模块名 CapISH），桥走真引擎；
 * - 不存在         → 只编桥的桩，插件照常编译，`getCapabilities().supported == false`
 *                    并给出原因（iOS CI 靠这条路径保持绿色）。
 *
 * 注意：本文件里第一个 `name:` 字面量必须是 CapacitorLinux —— `scripts/verify-ios.mjs`
 * 用它来识别插件包名并检查 App 端的 CapApp-SPM 引用（所以这里不要出现别的 name 字面量）。
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
    targets: linuxTargets()
)

/// 依据 `ios/CapISH.xcframework` 是否存在，决定是否把 iSH 引擎编进来。
func linuxTargets() -> [Target] {
    let packageDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    let frameworkPath = packageDir.appendingPathComponent("ios/CapISH.xcframework").path
    let hasISH = FileManager.default.fileExists(atPath: frameworkPath)

    // 离线 rootfs（Alpine aarch64 的 tar）作为 SPM 资源打进 App：
    //   - Resources/.keep 永远在（保证 Bundle.module 一定生成）；
    //   - Resources/rootfs.tar 由 fetch-ish-ios-runtime.sh 或 fetch-linux-runtime.py 拷进来，
    //     有它才会被声明成资源（没它时 start() 会回退到 Documents 里的路径并报 ROOTFS_MISSING）。
    let resourcesDir = packageDir.appendingPathComponent("ios/Sources/LinuxPlugin/Resources")
    var capishResources: [Resource] = [.copy("Resources/.keep")]
    if FileManager.default.fileExists(atPath: resourcesDir.appendingPathComponent("rootfs.tar").path) {
        capishResources.append(.copy("Resources/rootfs.tar"))
    }

    var list: [Target] = []
    if hasISH {
        list.append(.binaryTarget(name: "CapISH", path: "ios/CapISH.xcframework"))
    }
    // 引擎桥（C 接口）：有 iSH 头文件/库就走真引擎，否则桩
    list.append(
        .target(
            name: "CapISHBridge",
            dependencies: hasISH ? ["CapISH"] : [],
            path: "ios/Sources/CapISHBridge",
            publicHeadersPath: "include",
            // iSH 的 fakefs 用 sqlite3 存 inode 表（fs/fake-db.c），另外压缩/归档相关
            // 代码会用到 z/bz2/lzma —— 这些在 iOS SDK 里都有系统库，直接链即可。
            linkerSettings: hasISH ? [
                .linkedLibrary("sqlite3"),
                .linkedLibrary("z"),
                .linkedLibrary("bz2"),
                .linkedLibrary("lzma")
            ] : []
        )
    )
    list.append(
        .target(
            name: "LinuxPlugin",
            dependencies: [
                "CapISHBridge",
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/LinuxPlugin",
            resources: capishResources
        )
    )
    return list
}
