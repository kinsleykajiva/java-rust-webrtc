# Changelog — Port Updates & New Features

This page records what changed while porting the Windows-only JavaRust-Webrtc library to macOS/Linux
and bringing the media examples up to parity with webrtc-rs. It is a running log of updates, not a
formal release note.

## Platform port: Windows-only → macOS/Linux

The library was originally built and tested only on Windows. It now builds and runs on macOS
(Apple Silicon, verified) and should build unchanged on Linux (the native artifact is selected by
platform: `rust_webrtc_ffi.dll` / `librust_webrtc_ffi.dylib` / `librust_webrtc_ffi.so`).

Practical consequences:

- **Codesigning (macOS).** The `dylib` must be ad-hoc signed or the JVM refuses to load it under
  Hardened Runtime. After every `cargo build` run:
  ```bash
  codesign --force --sign - rust-webrtc-ffi/target/release/librust_webrtc_ffi.dylib
  ```
- **jextract regenerated.** The FFM bindings (`webrtc_ffi_h.java`) were regenerated with jextract 25
  to include the new FFI functions added below.

## String handling: UCS-2 → UTF-8

The `read_str` helper used by every FFI function that takes a C string previously decoded input as
**UCS-2** (`read_wide` over `u16`). That only worked on Windows, where the native side produced
UTF-16. The helper was changed to read **UTF-8 C strings** (`CStr`), matching what Rust `CString`s
actually contain. This fixed ICE candidate, SDP, and label round-trips on non-Windows platforms.

## Interceptor stack: default interceptors + RTCP forwarder

Previously every peer connection used `Registry::<NoopInterceptor>::new()` (no RTCP generation, no
NACK/TWCC). The connection builder now always calls `register_default_interceptors(...)` and wraps
the result with the new `RtcpForwarderInterceptor`. Effects:

- Sender/receiver reports, NACK, and TWCC are generated (so RTCP exists to observe).
- The RTCP forwarder queue is always present; `pollRtcp()` returns `[]` for peers whose config did
  not enable the forwarder.

The `Interceptor` trait is not dyn-compatible (it has a generic `with` method), so the registry type
is a concrete `Registry<RtcpForwarderInterceptor<ImplInterceptor>>` rather than `Arc<dyn Interceptor>`.

## New FFI functions (rust-webrtc-ffi)

| Function | Purpose |
|----------|---------|
| `webrtc_ffi_add_transceiver_from_kind_with_encoding` | Add a transceiver with a single explicit encoding (`rid`, `ssrc`, `mime_type`, `clock_rate`, `channels`). Backs simulcast layer control. |
| `webrtc_ffi_config_set_rtcp_forwarder` | Set `Config.rtcp_forwarder` so the forwarder interceptor is installed for that peer. |
| `webrtc_ffi_poll_rtcp` | Drain captured incoming RTCP as a JSON array of hex-encoded wire blobs. |

Supporting Rust additions:

- `mod rtcp_forwarder` — a `#[interceptor]` `RtcpForwarderInterceptor<P>` that captures
  `Packet::Rtcp` in `handle_read`, marshals each packet, and pushes it onto a bounded (8192)
  `Arc<Mutex<VecDeque<Vec<u8>>>>`.
- `Config.rtcp_forwarder: bool` field and an `rtcp_queue` on the live `Peer`.

## New Java API (io.github.kinsleykajiva.webrtc)

```java
// Configuration
Configuration Configuration.setRtcpForwarder(boolean enabled)

// PeerConnection
void   PeerConnection.addTransceiverFromKindWithEncoding(
            MediaKind kind, TransceiverDirection direction,
            String rid, long ssrc, String mimeType, long clockRate, int channels)
String PeerConnection.pollRtcp()   // JSON array of hex RTCP blobs, "[]" if none
```

`pollRtcp()` reads the native C string, copies it into a Java `String`, and immediately frees the
native buffer via `webrtc_ffi_free_string`.

## New helper classes (demo-code)

- `Av1Depacketizer` — Java port of `webrtc-rs::av1::depacketizer::Av1Depacketizer`. Reassembles AV1
  OBUs from RTP aggregation-header-framed payloads into a low-overhead AV1 bitstream.
- `IvfWriter` — minimal IVF (`DKIF`) muxer; writes the `AV01` fourcc header and one entry per frame.

## Media example demos — brought to parity

All seven webrtc-rs media examples now have a runnable Java demo. Four were unimplemented stubs
before this work; three (`rtp-forwarder`, `insertable-streams`, `rtp-to-webrtc`) already existed and
were verified/fixed.

| Demo | Status | Notes |
|------|--------|-------|
| `RtpForwarderDemo` | existing, verified | RTP bridge between two peer connections |
| `InsertableStreamsDemo` | existing, verified | Send/recv RTP transform |
| `SaveToDiskAv1Demo` | **new** | AV1 depacketizer + IVF writer; ffmpeg-validated |
| `SimulcastDemo` | **new (was stub)** | 3 layers via separate `TrackLocal` tracks |
| `SimulcastAddTransceiverFromKindDemo` | **new (was stub)** | 3 layers via `addTransceiverFromKindWithEncoding` |
| `RtcpProcessingDemo` | **new (was stub)** | `setRtcpForwarder` + `pollRtcp` |
| `RtpToWebRtcDemo` | existing, **fixed** | Switched from UDP-only to TCP loopback so it connects |

All seven pass on macOS. See [Media Examples](media-examples.md) for run commands and expected output.

## Transport fix for localhost tests

Every media example uses **TCP loopback** transport (`127.0.0.1`). A UDP-only configuration does not
gather usable host candidates on loopback, so connections stayed in `CONNECTING`. The answerer uses
`Configuration.useTcpOnly("127.0.0.1:<port>", DtlsRole.CLIENT)`; the offerer uses
`Configuration.setTransport("", "127.0.0.1:0", 0, NetworkType.TCP.value)`. Ports: 8450–8453.

## Documentation added

- `docs/simulcast.md` — both simulcast approaches.
- `docs/rtcp-processing.md` — RTCP forwarder + `pollRtcp`.
- `docs/save-to-disk-av1.md` — AV1 depacketization and IVF writing.
- `docs/rtp-to-webrtc.md` — RTP bridge into a peer connection.
- `docs/media-examples.md` — index of all seven media examples.
- `docs/api-reference.md` — updated with the three new methods.
- `docs/README.md` — updated table of guides.
