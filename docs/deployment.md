# Deployment

This document covers production deployment of the JavaRust-Webrtc library.

**Status**: This section is under development. The library is still in active development and a proper deployment guide will be available before the first stable release.

## What's Coming

The deployment guide will cover:

- **JAR packaging** -- How to bundle the library and native DLL into a single distributable package.
- **Native library loading** -- Strategies for placing `rust_webrtc_ffi.dll` (or `.so`/`.dylib`) where the JVM can find it.
- **Platform support** -- Windows, Linux, and macOS builds and cross-compilation.
- **Maven Central** -- Publishing the library as a Maven dependency.
- **Docker** -- Containerized deployment with the native library.
- **Resource management** -- Memory limits, thread pool sizing, and connection limits in production.
- **Monitoring** -- Using `StatsReport` for production monitoring and alerting.
- **Security** -- DTLS/SRTP configuration, certificate management, and TURN credential rotation.

## Current State

For now, deployment is manual:

1. Build the Rust FFI library: `cargo build --release` in `rust-webrtc-ffi/`
2. Build the Java library: `mvn clean install` in the project root
3. Copy the platform-native library (`rust_webrtc_ffi.dll` / `librust_webrtc_ffi.dylib` / `librust_webrtc_ffi.so`) alongside your Java application, or rely on the jar-embedded copy
4. Run with `--enable-native-access=ALL-UNNAMED`

## Platform Support

The native library is selected automatically by `NativeLibraryLoader` based on `os.name`:

| Platform | Artifact | Temp-file extension |
|----------|----------|--------------------|
| Windows  | `rust_webrtc_ffi.dll` | `.dll` |
| macOS    | `librust_webrtc_ffi.dylib` | `.dylib` |
| Linux    | `librust_webrtc_ffi.so` | `.so` |

The Maven build packages the correct artifact into the jar under `/native` via the per-OS profiles in the root `pom.xml`. On macOS the build also ad-hoc signs the dylib so it can be loaded by a hardened-runtime JVM.

To load a library from an explicit path (e.g. when developing the Rust side), set `-Dwebrtc.native.lib=<absolute-path>`.

## Known Limitations

- Windows, macOS, and Linux are all supported; macOS/Linux verification depends on the build host.
- The native library must be loadable (on the classpath as `/native/...`, on java.library.path, in the working directory, or via `-Dwebrtc.native.lib`).
- Thread pool sizes are fixed at build time (tokio runtime configuration).

## Roadmap

Before a stable release, we plan to address:

- [ ] Cross-platform native library packaging (platform-specific JARs)
- [ ] Maven Central publication
- [ ] Javadoc publishing
- [ ] Performance benchmarks
- [ ] Load testing documentation
- [ ] Security hardening guide
- [ ] CI/CD pipeline
- [ ] Automated releases
