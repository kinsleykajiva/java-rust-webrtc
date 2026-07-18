# JavaRust-Webrtc

A pure Java WebRTC library backed by a Rust FFI core. No browser dependency, no JNI, no JavaFX -- just Java 25 FFM (Foreign Function & Memory) calling into a Rust `webrtc-rs` implementation over C ABI.

The goal is to give Java developers a first-class WebRTC stack they can use from desktop apps, backend services, media servers, and anywhere else the JVM runs.

## Project Status

This library is under active development. The core peer connection, ICE, data channels, media tracks, and trickle ICE are working. More features and a proper release are coming soon.

## Requirements

| Tool | Version |
|------|---------|
| Java | 25+ (GraalVM CE 25 or any JDK 25 build) |
| Rust | 1.80+ with `stable-x86_64-pc-windows-msvc` target |
| Maven | 3.8+ |
| jextract | 25 (bundled with JDK 25 EA, or standalone build) |

Windows is the primary development platform. Linux and macOS support is planned.

## Building

```bash
# Build the Rust FFI library (creates rust_webrtc_ffi.dll)
cd rust-webrtc-ffi
cargo build --release
cd ..

# Build the Java library and demo code
mvn clean install
```

The Rust build produces `rust-webrtc-ffi/target/release/rust_webrtc_ffi.dll`. The Maven build uses jextract to generate FFM bindings from the C header and compiles everything together.

## Running Demos

```bash
# Copy the native DLL to the demo-code directory
cp rust-webrtc-ffi/target/release/rust_webrtc_ffi.dll demo-code/

# Run a demo
cd demo-code
java --enable-native-access=ALL-UNNAMED -cp "target/classes;target/dependency/*;." io.github.kinsleykajiva.Main
```

### Available Demos

| Demo | Description |
|------|-------------|
| `Main` | Basic peer connection with data channel messaging |
| `TrickleIceDemo` | Trickle ICE with host/srflx/relay/combined/flags modes |
| `IceRestartDemo` | ICE restart with candidate re-gathering |
| `IceTcpDemo` | TCP ICE candidates over loopback |
| `IceTcpActivePassiveDemo` | TCP active/passive role negotiation |
| `StatsDemo` | Fetching and displaying connection statistics |
| `PlayFromDiskH26xDemo` | H.264/H.265 video playback from file |
| `PlayFromDiskVpxDemo` | VP8/VP9 video playback from file |
| `PlayFromDiskRenegotiationDemo` | Track renegotiation during playback |
| `PlayFromDiskPlaylistControlDemo` | Playlist-style track switching |
| `RtpToWebRtcDemo` | Forwarding raw RTP packets into a peer connection |
| `AudioTranscoderDemo` | Audio transcoding bridge (Opus/G.722/PCMU/PCMA) |
| `SupportedCodecs` | Lists all codecs registered in the media engine |

## Architecture

```
Java Application
       |
       v
  Java FFM API  (io.github.kinsleykajiva.webrtc.*)
       |
       v
  jextract Bindings  (webrtc_ffi_h.java, generated)
       |
       v
  rust_webrtc_ffi.dll  (C ABI, blocking calls)
       |
       v
  webrtc-rs  (ICE, DTLS, SRTP, SCTP, RTP/RTCP)
```

Java calls into the Rust library through jextract-generated FFM bindings. Each FFI call crosses the Java/Rust boundary over a C ABI function. The Rust side manages async tokio runtimes internally and exposes a blocking C interface to Java.

Peer connections are handle-based: Java creates a peer, gets back a numeric handle, and passes it to subsequent calls. Callbacks from Rust back to Java use jextract upcall stubs.

## Core Classes

- **`WebRtc`** -- Library initialization and codec listing
- **`Configuration`** -- ICE servers, transport, port range, allocator flags
- **`PeerConnection`** -- The main API: create offer/answer, set descriptions, manage tracks and data channels
- **`SessionDescription`** -- SDP offer/answer wrapper
- **`DataChannel`** -- Reliable/unreliable data messaging
- **`TrackLocal`** -- Sending audio/video (sample-based or raw RTP)
- **`TrackRemote`** -- Receiving audio/video with RTP/RTCP callbacks
- **`StatsReport`** -- Connection statistics (ICE, RTP, candidate info)
- **`Codec`** -- Supported codec descriptor
- **`MimeTypes`** -- MIME type constants for all supported codecs
- **`PortAllocatorFlags`** -- Bitmask constants for candidate gathering control

## Documentation

Full documentation lives in the [docs/](docs/) directory:

- [Architecture](docs/architecture.md) -- How the Rust/Java bridge works
- [Getting Started](docs/getting-started.md) -- Step-by-step setup
- [API Reference](docs/api-reference.md) -- Core classes and methods
- [ICE and Transport](docs/ice-and-transport.md) -- ICE, STUN, TURN, TCP
- [Media Tracks](docs/media-tracks.md) -- Audio/video sending and receiving
- [Data Channels](docs/data-channels.md) -- Reliable messaging
- [Play from Disk](docs/play-from-disk.md) -- Reading and sending media files
- [Trickle ICE](docs/trickle-ice.md) -- Candidate trickling
- [Port Allocator](docs/port-allocator.md) -- Port range and flags
- [Statistics](docs/stats.md) -- Monitoring connections
- [Deployment](docs/deployment.md) -- Coming soon

## License

TBD.
