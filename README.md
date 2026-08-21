# JavaRust-Webrtc

A pure Java WebRTC library backed by a Rust FFI core. No browser dependency, no JNI, no JavaFX -- just Java 25 FFM (Foreign Function & Memory) calling into a Rust `webrtc-rs` implementation over C ABI.

The goal is to give Java developers a first-class WebRTC stack they can use from desktop apps, backend services, media servers, and anywhere else the JVM runs.

## Project Status

This library is under active development. The core peer connection, ICE, data channels, media tracks, and trickle ICE are working, and all seven webrtc-rs media examples now run on macOS (see [Media Examples](docs/media-examples.md)). The desktop module provides JavaFX-based device access for camera and microphone capture. More features and a proper release are coming soon.

## Requirements

| Tool | Version |
|------|---------|
| Java | 22+ (FFM is final in 22; the build targets JDK 25) |
| Rust | 1.80+ (`stable` for your platform) |
| Maven | 3.8+ |
| jextract | 25 (bundled with JDK 25, or standalone build) |

The library is cross-platform: Windows, macOS, and Linux are supported. The native artifact name differs per platform (`rust_webrtc_ffi.dll` on Windows, `librust_webrtc_ffi.dylib` on macOS, `librust_webrtc_ffi.so` on Linux) and is selected automatically.

## Building

```bash
# Build the Rust FFI library (creates the platform-native shared object)
cd rust-webrtc-ffi
cargo build --release
cd ..

# Build the Java library and demo code
mvn clean install
```

The Rust build produces `rust-webrtc-ffi/target/release/<native lib>` (e.g. `librust_webrtc_ffi.dylib` on macOS). The Maven build uses jextract to generate FFM bindings from the C header and compiles everything together.

## Running Demos

```bash
# Copy the native library to the demo-code directory (use the name for your OS)
cp rust-webrtc-ffi/target/release/librust_webrtc_ffi.dylib demo-code/   # macOS
# cp rust-webrtc-ffi/target/release/rust_webrtc_ffi.dll demo-code/      # Windows
# cp rust-webrtc-ffi/target/release/librust_webrtc_ffi.so demo-code/    # Linux

# Run a demo (use ':' as the classpath separator on macOS/Linux, ';' on Windows)
cd demo-code
java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/dependency/*:." io.github.kinsleykajiva.Main
```

`mvn clean install -Djextract.home=/Users/xuser/jextract-25`

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

### Media Examples (webrtc-rs ports)

| Demo | Description |
|------|-------------|
| `RtpForwarderDemo` | RTP bridge between two peer connections |
| `InsertableStreamsDemo` | Send/recv RTP transform (Insertable Streams pattern) |
| `SaveToDiskAv1Demo` | Receive AV1 RTP, depacketize, write an IVF file |
| `SimulcastDemo` | 3-layer simulcast via separate `TrackLocal` tracks |
| `SimulcastAddTransceiverFromKindDemo` | 3-layer simulcast via `addTransceiverFromKindWithEncoding` |
| `RtcpProcessingDemo` | Observe incoming RTCP via `setRtcpForwarder` + `pollRtcp` |

### Desktop Demos

| Demo | Description |
|------|-------------|
| `ListDevices` | Lists all available cameras, microphones, and speakers |
| `MicrophoneCaptureDemo` | Captures microphone audio and sends it over WebRTC |
| `CameraCaptureDemo` | Captures webcam video and sends it over WebRTC |
| `VideoCallApp` | Full video call UI with device selection and controls |

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
   rust_webrtc_ffi  (C ABI, blocking calls; .dll / .dylib / .so per platform)
        |
        v
   webrtc-rs  (ICE, DTLS, SRTP, SCTP, RTP/RTCP)
```

The project has two main layers:

- **`library`** -- Pure Java WebRTC core. Server-side, no GUI dependencies. Uses Java FFM to call into Rust.
- **`desktop`** -- Optional desktop layer. Adds JavaFX UI, camera/microphone capture, and device management. Depends on `library` but does not modify it.

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

### Desktop Module (`desktop`)

- **`DeviceEnumerator`** -- Lists cameras, microphones, and speakers on the system
- **`AudioDevice`** / **`VideoDevice`** -- Device descriptors
- **`AudioCapture`** -- Microphone capture piped to WebRTC via `TargetDataLine`
- **`VideoCapture`** -- Webcam capture piped to WebRTC via webcam-capture library
- **`BitrateConfig`** -- Audio/video quality presets (low bandwidth, default, high quality, screen share)
- **`VideoCallWindow`** -- JavaFX video call UI with device selectors, local/remote preview, and call controls

## Documentation

Full documentation lives in the [docs/](docs/) directory:

- [Architecture](docs/architecture.md) -- How the Rust/Java bridge works
- [Getting Started](docs/getting-started.md) -- Step-by-step setup
- [API Reference](docs/api-reference.md) -- Core classes and methods
- [ICE and Transport](docs/ice-and-transport.md) -- ICE, STUN, TURN, TCP
- [Media Tracks](docs/media-tracks.md) -- Audio/video sending and receiving
- [Data Channels](docs/data-channels.md) -- Reliable messaging
- [Play from Disk](docs/play-from-disk.md) -- Reading and sending media files
- [Save to Disk (AV1)](docs/save-to-disk-av1.md) -- Receiving AV1 RTP and writing IVF
- [Simulcast](docs/simulcast.md) -- Multi-layer publishing
- [RTCP Processing](docs/rtcp-processing.md) -- Observing incoming RTCP
- [RTP-to-WebRTC](docs/rtp-to-webrtc.md) -- Bridging raw RTP into a peer connection
- [Media Examples](docs/media-examples.md) -- Index of the seven media example demos
- [Trickle ICE](docs/trickle-ice.md) -- Candidate trickling
- [Port Allocator](docs/port-allocator.md) -- Port range and flags
- [Statistics](docs/stats.md) -- Monitoring connections
- [Changelog](docs/changelog.md) -- What changed in the macOS/Linux port and new features
- [Deployment](docs/deployment.md) -- Coming soon

## License

TBD.
