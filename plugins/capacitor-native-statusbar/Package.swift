// swift-tools-version: 5.9

import PackageDescription

// 注意：包名必须与 Capacitor CLI 由 npm 包名推导出来的名字一致，
// 否则 ios/App/CapApp-SPM/Package.swift 里的 .package(name:) 会找不到这个包。
// capacitor-native-statusbar -> CapacitorNativeStatusbar（只有首字母和分隔符后大写）
let package = Package(
    name: "CapacitorNativeStatusbar",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapacitorNativeStatusbar",
            targets: ["NativeStatusBarPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "NativeStatusBarPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/NativeStatusBarPlugin")
    ]
)
