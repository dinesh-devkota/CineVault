// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "CineVaultCore",
    platforms: [.tvOS(.v17), .iOS(.v17), .macOS(.v13)],
    products: [
        .library(name: "CineVaultCore", targets: ["CineVaultCore"]),
    ],
    targets: [
        .target(name: "CineVaultCore"),
        .testTarget(name: "CineVaultCoreTests", dependencies: ["CineVaultCore"]),
    ]
)
