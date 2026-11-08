// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "CapacitorCppBridge",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapacitorCppBridge",
            targets: ["CppBridgePlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        // 纯 C/C++ target：SwiftPM 要求 Swift 和 C/C++ 分 target，不能混在一个 target 里。
        // path 指向插件根目录的 shared/core（与 Android 的 CMake 共享同一份源码）。
        .target(
            name: "CppCore",
            path: "shared/core",
            publicHeadersPath: "include",
            cSettings: [
                .headerSearchPath("include")
            ],
            cxxSettings: [
                .headerSearchPath("include")
            ]
        ),
        // Swift 桥 target：import CppCore 后直接调用 C 函数。
        .target(
            name: "CppBridgePlugin",
            dependencies: [
                "CppCore",
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/CppBridgePlugin"
        )
    ],
    cxxLanguageStandard: .cxx17
)
