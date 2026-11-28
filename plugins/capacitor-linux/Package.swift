// swift-tools-version: 5.9
import PackageDescription

/**
 * capacitor-linux 的 iOS 侧。
 *
 * 引擎是 **iSH-ARM64**（`everettjf/dsh-ios` 里的 `ish-arm64/`，Asbestos 线程码解释器，
 * AArch64 客户机）。它不在本仓库里：需要 macOS 用
 * `scripts/fetch-ish-ios-runtime.sh` 构建成 xcframework 后链接进宿主 App。
 *
 * 为了让 iOS CI（只编译、不链接引擎）也能过，这里：
 * - `CapISHBridge` 是一个 C/ObjC target，用 `__has_include` 两路编译：
 *   有 `ISHShellExecutor.h` 就调真引擎，没有就编成"引擎未链接"的桩；
 * - `LinuxPlugin` 只依赖桩接口，所以**有没有引擎都能编译**，运行时再判断。
 *
 * 引擎就绪后（xcframework 已生成），在宿主 App 的 target 里链接它即可；本 Package.swift
 * 不需要改（真引擎通过 ObjC 运行时按类名查找，弱耦合）。head 搜索路径通过
 * `CAPISH_ISH_HEADER_SEARCH_PATH` 环境变量/附加 cSettings 提供，见 fetch 脚本输出。
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
        // 引擎桥（C 接口）：有 iSH 头文件走真引擎，没有就是桩
        .target(
            name: "CapISHBridge",
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
)
