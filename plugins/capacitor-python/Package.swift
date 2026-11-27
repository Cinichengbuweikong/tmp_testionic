// swift-tools-version: 5.9
import PackageDescription

/**
 * iOS 原生 CPython（BeeWare Python-Apple-support）。
 *
 * - `Python.xcframework`（静态 libPython + 头文件）由 `scripts/fetch-python-ios-runtime.mjs`
 *   下载生成，**不入库**；
 * - `python/` 是裁剪后的纯 Python stdlib（SPM 资源，运行时通过 `Bundle.module` 定位）；
 * - `PythonShim` 是一个 C target：把 Py_* 调用包成稳定 API，Swift 只依赖它
 *   （binaryTarget 不会给 Swift 生成 module map）。
 */
let package = Package(
    name: "CapacitorPython",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "CapacitorPython", targets: ["PythonPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        // 静态 libPython（按架构切片），链接进 App
        .binaryTarget(name: "Python", path: "ios/Python.xcframework"),
        // C 包装层：Py_Initialize / PyRun_String / 回调桥
        .target(
            name: "PythonShim",
            path: "ios/Sources/PythonShim",
            cSettings: [
                .headerSearchPath("python-headers")
            ]
        ),
        .target(
            name: "PythonPlugin",
            dependencies: [
                "Python",
                "PythonShim",
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/PythonPlugin",
            resources: [
                // 纯 Python stdlib + capython/runtime.py，打进 App 的 SPM 资源 bundle
                .copy("python")
            ],
            linkerSettings: [
                .linkedLibrary("z"),
                .linkedLibrary("bz2"),
                .linkedLibrary("lzma")
            ])
    ]
)
