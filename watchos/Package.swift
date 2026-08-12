// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "WristRelayWatchOS",
    platforms: [
        .watchOS("9.0")
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.26.0")
    ],
    targets: [
        .target(
            name: "WristRelayWatchOS",
            dependencies: [
                .product(name: "SwiftProtobuf", package: "swift-protobuf")
            ],
            path: "WristRelayWatchOS"
        )
    ]
)
