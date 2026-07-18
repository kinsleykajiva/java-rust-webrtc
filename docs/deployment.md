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
3. Copy `rust_webrtc_ffi.dll` alongside your Java application
4. Run with `--enable-native-access=ALL-UNNAMED`

## Known Limitations

- Windows is the only fully tested platform. Linux and macOS builds should work but have not been verified end-to-end.
- The native DLL must be on the java.library.path or in the working directory.
- There is no automatic platform detection for the native library yet.
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
