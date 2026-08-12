// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "WatchBridgeWatchOS",
    platforms: [
        .watchOS("9.0")
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.26.0")
    ],
    targets: [
        .target(
            name: "WatchBridgeWatchOS",
            dependencies: [
                .product(name: "SwiftProtobuf", package: "swift-protobuf")
            ],
            path: "WatchBridgeWatchOS"
        )
    ]
)
