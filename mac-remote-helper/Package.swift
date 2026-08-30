// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "WireKeyRemote",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "WireKeyRemote", targets: ["WireKeyRemote"])
    ],
    targets: [
        .executableTarget(
            name: "WireKeyRemote",
            linkerSettings: [
                .linkedFramework("AppKit"),
                .linkedFramework("CoreBluetooth"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("IOKit")
            ]
        )
    ],
    swiftLanguageModes: [.v5]
)
