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
| [Trickle ICE](trickle-ice.md) | Candidate trickling, gathering states |
| [Port Allocator](port-allocator.md) | Port range filtering, allocator flags |
| [Statistics](stats.md) | Fetching and reading connection stats |
| [Deployment](deployment.md) | Production deployment (coming soon) |

## Quick Start

If you just want to get something running:

```bash
cargo build --release          # in rust-webrtc-ffi/
mvn clean install              # in project root
cp rust-webrtc-ffi/target/release/rust_webrtc_ffi.dll demo-code/
java --enable-native-access=ALL-UNNAMED \
     -cp "demo-code/target/classes;demo-code/target/dependency/*;." \
     io.github.kinsleykajiva.Main
```

This runs the basic data channel demo with two peer connections on localhost.
