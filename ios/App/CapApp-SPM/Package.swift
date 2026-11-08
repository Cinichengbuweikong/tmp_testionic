// swift-tools-version: 5.9
import PackageDescription

// DO NOT MODIFY THIS FILE - managed by Capacitor CLI commands
let package = Package(
    name: "CapApp-SPM",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapApp-SPM",
            targets: ["CapApp-SPM"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", exact: "8.6.0"),
        .package(name: "CapacitorStatusBar", path: "../../../node_modules/@capacitor/status-bar"),
        .package(name: "CapacitorForegroundState", path: "../../../plugins/capacitor-foreground-state"),
        .package(name: "CapacitorCppBridge", path: "../../../plugins/capacitor-cpp-bridge"),
        .package(name: "CapacitorDataChannel", path: "../../../plugins/capacitor-data-channel"),
        .package(name: "CapacitorFullscreen", path: "../../../plugins/capacitor-fullscreen"),
        .package(name: "CapacitorKeyboardAvoiding", path: "../../../plugins/capacitor-keyboard-avoiding"),
        .package(name: "CapacitorNativeBasics", path: "../../../plugins/capacitor-native-basics"),
        .package(name: "CapacitorNativeStatusbar", path: "../../../plugins/capacitor-native-statusbar"),
        .package(name: "CapacitorNativeRotation", path: "../../../plugins/capacitor-native-rotation"),
        .package(name: "CapacitorNativeFiles", path: "../../../plugins/capacitor-native-files"),
        .package(name: "CapacitorSetForceDark", path: "../../../plugins/capacitor-set-force-dark"),
        .package(name: "CapacitorSystemInfo", path: "../../../plugins/capacitor-system-info"),
        .package(name: "CapacitorWebviewToEdge", path: "../../../plugins/capacitor-webview-to-edge")
    ],
    targets: [
        .target(
            name: "CapApp-SPM",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                .product(name: "CapacitorStatusBar", package: "CapacitorStatusBar"),
                .product(name: "CapacitorForegroundState", package: "CapacitorForegroundState"),
                .product(name: "CapacitorCppBridge", package: "CapacitorCppBridge"),
                .product(name: "CapacitorDataChannel", package: "CapacitorDataChannel"),
                .product(name: "CapacitorFullscreen", package: "CapacitorFullscreen"),
                .product(name: "CapacitorKeyboardAvoiding", package: "CapacitorKeyboardAvoiding"),
                .product(name: "CapacitorNativeBasics", package: "CapacitorNativeBasics"),
                .product(name: "CapacitorNativeStatusbar", package: "CapacitorNativeStatusbar"),
                .product(name: "CapacitorNativeRotation", package: "CapacitorNativeRotation"),
                .product(name: "CapacitorNativeFiles", package: "CapacitorNativeFiles"),
                .product(name: "CapacitorSetForceDark", package: "CapacitorSetForceDark"),
                .product(name: "CapacitorSystemInfo", package: "CapacitorSystemInfo"),
                .product(name: "CapacitorWebviewToEdge", package: "CapacitorWebviewToEdge")
            ]
        )
    ]
)
