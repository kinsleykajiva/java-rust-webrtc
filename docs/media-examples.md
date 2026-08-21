# Media Examples

webrtc-rs ships a set of canonical media examples. This library provides a Java port of each one in
`demo-code/`. All seven run end-to-end on a single machine using TCP loopback transport. This page
is the index; each example also has a deeper guide (links below).

| # | webrtc-rs example | Java demo | Guide |
|---|-------------------|-----------|-------|
| 1 | `rtp-forwarder` | `RtpForwarderDemo` | — (see below) |
| 2 | `insertable-streams` | `InsertableStreamsDemo` | — (see below) |
| 3 | `save-to-disk-av1` | `SaveToDiskAv1Demo` | [Save to Disk (AV1)](save-to-disk-av1.md) |
| 4 | `simulcast` | `SimulcastDemo` | [Simulcast](simulcast.md) |
| 5 | `simulcast_add_transceiver_from_kind` | `SimulcastAddTransceiverFromKindDemo` | [Simulcast](simulcast.md) |
| 6 | `rtcp-processing` | `RtcpProcessingDemo` | [RTCP Processing](rtcp-processing.md) |
| 7 | `rtp-to-webrtc` | `RtpToWebRtcDemo` | [RTP-to-WebRTC](rtp-to-webrtc.md) |

## 1. RtpForwarderDemo (`rtp-forwarder`)

Forwards RTP *between two peer connections*: an upstream sender writes RTP into one connection, and a
bridge copies those packets into a downstream `TrackLocal` on a second connection. Demonstrates
`TrackLocal.writeRtp` and the `TrackRemote` RTP callback. Expects: `Source sent: 200`, `Forwarded:
200`, `Sink received: 200`.

```bash
java --enable-native-access=ALL-UNNAMED -Dwebrtc.native.lib=... \
     -cp "demo-code/target/classes:library/target/classes:<slf4j>:<logback>" \
     io.github.kinsleykajiva.RtpForwarderDemo
```

## 2. InsertableStreamsDemo (`insertable-streams`)

Applies a user-defined transform to media on the way out and inverts it on the way in
(Insertable Streams / "frame encryption" pattern). The sender XOR-scrambles RTP payloads; the
receiver XORs them back. Shows how to hook the RTP callback pipeline on both send and receive.
Expects: `Transformed sent: 200`, `Recovered (ok): 200`.

## 3. SaveToDiskAv1Demo (`save-to-disk-av1`)

Receives AV1 RTP, depacketizes it with `Av1Depacketizer` (Java port of webrtc-rs'
AV1 depacketizer), and writes an IVF file with `IvfWriter`. Validated by decoding the output with
ffmpeg. See [Save to Disk (AV1)](save-to-disk-av1.md).

## 4 & 5. SimulcastDemo / SimulcastAddTransceiverFromKindDemo (`simulcast`)

Two ways to publish a 3-layer simulcast (`q`/`h`/`f`): multiple `TrackLocal` tracks, or
`addTransceiverFromKindWithEncoding` with explicit RID/SSRC/codec. The receiver observes all three
layers. See [Simulcast](simulcast.md). Both expect `Layers received: 3`, `Total RTP received: 150`.

## 6. RtcpProcessingDemo (`rtcp-processing`)

Installs the built-in RTCP forwarder interceptor (`Configuration.setRtcpForwarder(true)`) and
drains incoming RTCP with `PeerConnection.pollRtcp()`. Captures the sender's RTCP sender reports. See
[RTCP Processing](rtcp-processing.md). Expects `RTCP packets captured: 5` (or more).

## 7. RtpToWebRtcDemo (`rtp-to-webrtc`)

Bridges external raw RTP packets into a peer connection via a `TrackLocal` RTP track, plus a control
data channel. See [RTP-to-WebRTC](rtp-to-webrtc.md). Expects `Connected: true` and the data-channel
message `rtp-forwarding-active`.

## How to run any demo

Build the native library and copy it next to (or point at) the demos, then run a class:

```bash
# 1. Build the Rust FFI core
cargo build --release --manifest-path rust-webrtc-ffi/Cargo.toml

# 2. Build the Java classes
mvn -q -pl library,demo-code install -DskipTests

# 3. Run (macOS/Linux). On macOS you must codesign the dylib once after each build:
codesign --force --sign - rust-webrtc-ffi/target/release/librust_webrtc_ffi.dylib

DYLIB=$PWD/rust-webrtc-ffi/target/release/librust_webrtc_ffi.dylib
CP="demo-code/target/classes:library/target/classes:\
$JAVA_HOME/lib/slf4j-api-2.0.13.jar:\
$JAVA_HOME/lib/logback-classic-1.5.19.jar:\
$JAVA_HOME/lib/logback-core-1.5.19.jar"

java --enable-native-access=ALL-UNNAMED \
     -Dwebrtc.native.lib="$DYLIB" \
     -cp "$CP" \
     io.github.kinsleykajiva.<DemoName>
```

Replace `librust_webrtc_ffi.dylib` with `rust_webrtc_ffi.dll` (Windows) or
`librust_webrtc_ffi.so` (Linux) on those platforms.

## Loopback transport note

All media examples run two peers on `127.0.0.1`. A UDP-only configuration does not gather usable
candidates on loopback, so the demos use TCP transport: the answerer calls
`Configuration.useTcpOnly("127.0.0.1:<port>", DtlsRole.CLIENT)` and the offerer calls
`Configuration.setTransport("", "127.0.0.1:0", 0, NetworkType.TCP.value)`. The ports used:

| Demo | Port |
|------|------|
| `SimulcastAddTransceiverFromKindDemo` | 8450 |
| `SimulcastDemo` | 8451 |
| `RtcpProcessingDemo` | 8452 |
| `RtpToWebRtcDemo` | 8453 |

## Status

All seven media examples are implemented and verified to pass on macOS (Apple Silicon). The FFI
functions and Java wrappers they rely on are documented in the [API Reference](api-reference.md) and
summarized in the [Changelog](changelog.md).
