# Documentation

This directory contains the full documentation for the JavaRust-Webrtc library.

## Guides

| Document | What it covers |
|----------|---------------|
| [Architecture](architecture.md) | Rust/Java FFI bridge, threading model, handle system |
| [Getting Started](getting-started.md) | Prerequisites, building, first run |
| [API Reference](api-reference.md) | Core classes, methods, enums |
| [ICE and Transport](ice-and-transport.md) | ICE candidates, STUN, TURN, TCP transport |
| [Media Tracks](media-tracks.md) | Audio/video tracks, codecs, RTP |
| [Data Channels](data-channels.md) | Reliable data messaging between peers |
| [Play from Disk](play-from-disk.md) | Reading media files and sending them over WebRTC |
| [Save to Disk (AV1)](save-to-disk-av1.md) | Receiving AV1 RTP, depacketizing, writing IVF |
| [Simulcast](simulcast.md) | Multi-layer publishing (two approaches) |
| [RTCP Processing](rtcp-processing.md) | Observing incoming RTCP via the forwarder interceptor |
| [RTP-to-WebRTC](rtp-to-webrtc.md) | Bridging raw RTP into a peer connection |
| [Media Examples](media-examples.md) | Index of the seven webrtc-rs media example demos |
| [Packaging & Distribution](packaging.md) | Maven/Gradle imports, native artifacts, Maven Central plan |
| [Trickle ICE](trickle-ice.md) | Candidate trickling, gathering states |
| [Port Allocator](port-allocator.md) | Port range filtering, allocator flags |
| [Statistics](stats.md) | Fetching and reading connection stats |
| [Desktop Module](desktop.md) | Camera, microphone, JavaFX UI, device access |
| [Deployment](deployment.md) | Production deployment (coming soon) |
| [Changelog](changelog.md) | What changed in the macOS/Linux port and new features |

## Quick Start

If you just want to get something running:

```bash
cargo build --release          # in rust-webrtc-ffi/
mvn clean install              # in project root
cp rust-webrtc-ffi/target/release/librust_webrtc_ffi.dylib demo-code/   # macOS
# cp rust-webrtc-ffi/target/release/rust_webrtc_ffi.dll demo-code/       # Windows
# cp rust-webrtc-ffi/target/release/librust_webrtc_ffi.so demo-code/     # Linux
java --enable-native-access=ALL-UNNAMED \
     -cp "demo-code/target/classes:demo-code/target/dependency/*:." \
     io.github.kinsleykajiva.Main
```

This runs the basic data channel demo with two peer connections on localhost.
