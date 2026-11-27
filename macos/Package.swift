// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MacBridge",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .library(
            name: "MacBridgeCore",
            targets: ["MacBridgeCore"]
        ),
        .executable(
            name: "MacBridge",
            targets: ["MacBridgeApp"]
        )
    ],
    targets: [
        .target(
            name: "MacBridgeCore",
            path: "Sources/MacBridgeCore"
        ),
        .executableTarget(
            name: "MacBridgeApp",
            dependencies: ["MacBridgeCore"],
            path: "Sources/MacBridgeApp"
        ),
        .testTarget(
            name: "MacBridgeCoreTests",
            dependencies: ["MacBridgeCore"],
            path: "Tests/MacBridgeCoreTests"
        )
    ]
)
