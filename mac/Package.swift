// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "Nexo",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "Nexo", path: "Sources/Nexo")
    ]
)
