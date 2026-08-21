# RTCP Processing

RTCP carries the control feedback for a media session: sender reports (SR), receiver reports (RR),
NACKs, PLI/FIR keyframe requests, transport-wide congestion control (TWCC), and more. By default
this feedback is consumed internally by webrtc-rs and is invisible to your application. This guide
covers how to **observe the incoming RTCP** from Java, which is useful for custom monitoring,
recording, or feeding an SFU/MCU.

## The built-in RTCP forwarder interceptor

When you enable it, the library installs a built-in `RtcpForwarderInterceptor` (a Rust
`#[interceptor]`) into every peer connection's interceptor chain. On every incoming packet it
inspects the `Packet::Rtcp` variant, marshals each RTCP packet to its wire bytes, and pushes them
onto a shared queue. Java drains that queue with `pollRtcp()`.

```
        network
           |
           v
   RtcpForwarderInterceptor::handle_read   <-- captures Packet::Rtcp
           |
           v  (also passes the packet downstream, unchanged)
     default RTCP interceptors (SR/RR/NACK/TWCC)
           |
           v
      PeerConnection
```

Enable it on a `Configuration`:

```java
Configuration cfg = Configuration.create()
        .useTcpOnly("127.0.0.1:8452", DtlsRole.CLIENT)
        .setRtcpForwarder(true);
```

The FFI call is `webrtc_ffi_config_set_rtcp_forwarder`; the Java wrapper is:

```java
public Configuration Configuration.setRtcpForwarder(boolean enabled)
```

## Draining RTCP with pollRtcp()

`pollRtcp()` removes and returns every captured packet as a JSON array of hex-encoded wire blobs.
It returns `"[]"` when the forwarder was not enabled or the queue is empty. The underlying C string
is freed automatically after the Java `String` is produced.

```java
String rtcp = receiver.pollRtcp();   // e.g. ["80c80006...","81c90004..."]
```

Each hex string is a complete RTCP packet (SR, RR, NACK, etc.) exactly as it arrived on the wire.
You can decode it back into a typed RTCP packet with webrtc-rs (`rtcp::packet::unmarshal`) if you
need to inspect fields, or forward the raw bytes to another system. To count captured packets in a
demo you can simply count the `"..."` entries in the array.

```java
int count = (!rtcp.equals("[]"))
        ? rtcp.replace("[","").replace("]","").replace("\"","").split(",").length
        : 0;
```

The FFI call behind this is `webrtc_ffi_poll_rtcp(peer)`.

## Why the default interceptors are always installed

The forwarder sits on top of webrtc-rs' **default interceptor chain**
(`register_default_interceptors`). That chain is what actually *generates* sender/receiver reports
and processes TWCC/NACK. If it were absent, a sender would never emit RTCP SRs, so a receiver would
have nothing to capture. All peer connections in this library therefore use the default interceptors
plus the forwarder, regardless of whether the forwarder is enabled for a given peer.

## Example: RtcpProcessingDemo

A receiver installs the forwarder and a sender publishes a video stream. After the connection is up,
the sender writes ~100 RTP packets; the receiver then polls RTCP in a loop. The sender's RTCP sender
reports are captured and counted.

```java
receiver = PeerConnection.create(tcpAnswerer(8452).setRtcpForwarder(true), observer);
receiver.addTransceiver(MediaKind.VIDEO, TransceiverDirection.RECV_ONLY);

sender = PeerConnection.create(tcpOfferer(), observer);
TrackLocal track = TrackLocal.createRtpTrack(
        MediaKind.VIDEO, "rtcp", "video", "video", TrackLocal.randomSsrc(), "video/VP8", 90_000);
sender.addTrack(track);
// ... negotiate, connect, write 100 RTP packets ...

int total = 0;
for (int i = 0; i < 25; i++) {
    total += countPackets(receiver.pollRtcp());
    Thread.sleep(200);
}
System.out.println("RTCP packets captured: " + total);
```

Run it:

```bash
java --enable-native-access=ALL-UNNAMED \
     -Dwebrtc.native.lib=rust-webrtc-ffi/target/release/librust_webrtc_ffi.dylib \
     -cp "demo-code/target/classes:library/target/classes:<slf4j>:<logback>" \
     io.github.kinsleykajiva.RtcpProcessingDemo
```

Expected output:

```
RTP received by receiver: 100
RTCP packets captured:    5
Sample RTCP (hex blobs):  ["80c800066cfe3b53ee32b65999049dde000521540000006400000190"]
SUCCESS: incoming RTCP observed and processed via pollRtcp().
```

## Notes

- RTCP is emitted on its own schedule (webrtc-rs generates sender reports periodically once media
  is flowing). Poll for a few seconds after media starts, as in the demo.
- The queue is bounded (8192 packets); once full, older entries are dropped silently.
- RTCP captured is **incoming** RTCP only (peer -> you). To send feedback (e.g. a PLI) use
  `TrackRemote.writeRtcpPli()`, described in [Media Tracks](media-tracks.md).
- The forwarder is cheap to leave enabled; it only copies RTCP packets that already arrived.
