# Prebuilt native libraries (rust-webrtc-ffi cdylib)

This directory holds the compiled native shared libraries that back the Java library. They are
packaged per platform and published as classified Maven artifacts
(`io.github.kinsleykajiva:JavaRust-Webrtc-native:0.1.0:<classifier>`).

| Platform | Classifier | File |
|----------|-----------|------|
| macOS (Apple Silicon) | `osx-aarch_64` | `osx-aarch_64/librust_webrtc_ffi.dylib` |
| Windows (x86-64) | `windows-x86_64` | `windows-x86_64/rust_webrtc_ffi.dll` |
| Linux (x86-64) | `linux-x86_64` | `linux-x86_64/librust_webrtc_ffi.so` |

## Building a missing binary

The macOS and Windows binaries are committed. To produce the Linux `.so` (or any other target),
build the Rust crate on that OS/arch and copy the output here:

```bash
cd rust-webrtc-ffi
cargo build --release
# macOS:  cp target/release/librust_webrtc_ffi.dylib ../native/osx-aarch_64/
# Windows: cp target/release/rust_webrtc_ffi.dll      ../native/windows-x86_64/
# Linux:  cp target/release/librust_webrtc_ffi.so     ../native/linux-x86_64/
```

On macOS the dylib must be ad-hoc signed before the JVM can load it:

```bash
codesign --force --sign - native/osx-aarch_64/librust_webrtc_ffi.dylib
```
