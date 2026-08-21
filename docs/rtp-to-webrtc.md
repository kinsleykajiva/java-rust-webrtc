# RTP-to-WebRTC

The `rtp-to-webrtc` example shows how to take a stream of **raw RTP packets from an external
source** (a media server, a capture device, another WebRTC peer, or a test generator) and forward
them into a `PeerConnection` as if they originated locally. This is the building block for an
RTP/WebRTC bridge or gateway.

## The idea

A `TrackLocal` RTP track accepts complete RTP packets instead of decoded samples. You build the RTP
packet yourself (12-byte RTP header + payload) and hand it to the track. The library rewrites the
SSRC in the packet header to the SSRC you declared when creating the track, then encrypts and sends
it as SRTP to the remote peer.

```
 external RTP source
        |
        v  byte[] rtpPacket
  TrackLocal (RTP track).writeRtp(packet)
        |
        v
   PeerConnection  --SRTP-->  remote peer
```

## Building an RTP packet

The RTP header is fixed at 12 bytes (more if CSRCs/extensions are present; this example uses the
basic header):

```java
private static byte[] buildRtpPacket(int ssrc, int payloadType,
                                     int seq, int timestamp, byte[] payload) {
    byte[] packet = new byte[12 + payload.length];
    ByteBuffer buf = ByteBuffer.wrap(packet);
    buf.put((byte) 0x80);                       // V=2, no padding/extension/CSRC
    buf.put((byte) (payloadType & 0xFF));
    buf.putShort((short) (seq & 0xFFFF));       // sequence number
    buf.putInt(timestamp);                      // RTP timestamp
    buf.putInt(ssrc);                           // SSRC
    buf.put(payload);                           // payload (e.g. VP8 frame chunk)
    return packet;
}
```

Create the track with `createRtpTrack` and add it:

```java
int ssrc = TrackLocal.randomSsrc();
TrackLocal video = TrackLocal.createRtpTrack(
        MediaKind.VIDEO, "rtp-stream", "vp8-video", "VP8 RTP Track",
        ssrc, "video/VP8", 90_000);
sender.addTrack(video);

for (int seq = 1; seq <= N; seq++) {
    byte[] payload = nextRtpPayload();
    video.writeRtp(buildRtpPacket(ssrc, 96, seq, seq * 3000, payload));
}
```

## The demo

`RtpToWebRtcDemo` runs two peers on TCP loopback (answerer on port `8453`). The offerer creates an
RTP track, builds VP8-framed RTP packets, and streams them. It also opens a data channel so the two
peers can exchange a simple control message (`"rtp-forwarding-active"`). The receiver prints the
data-channel message and will receive the forwarded RTP as `onTrack` RTP packets.

Because the peers run on `127.0.0.1`, the demo uses TCP transport (`useTcpOnly` on the answerer and
`setTransport(..., NetworkType.TCP.value)` on the offerer) — a UDP-only configuration does not
gather usable candidates on loopback.

## Running the demo

```bash
java --enable-native-access=ALL-UNNAMED \
     -Dwebrtc.native.lib=rust-webrtc-ffi/target/release/librust_webrtc_ffi.dylib \
     -cp "demo-code/target/classes:library/target/classes:<slf4j>:<logback>" \
     io.github.kinsleykajiva.RtpToWebRtcDemo
```

Expected output:

```
[Offerer] state: CONNECTED
[Answerer] state: CONNECTED
Connected: true
[Answerer] DC open
[Offerer] DC open
[Answerer] DC received: rtp-forwarding-active
```

## Notes

- The payload type in the RTP header (e.g. `96`) must match what the negotiated SDP assigns. Inspect
  `pc.senderGetPayloadType(senderId)` after negotiation if you need the exact value; for VP8 the
  demo hard-codes `96`, which is what webrtc-rs negotiates.
- The library rewrites the SSRC, so the SSRC you put in the packet header is overwritten — but you
  still must supply a valid 32-bit value (the demo reuses the track's SSRC for clarity).
- This is the same mechanism used internally by `RtpForwarderDemo`, which forwards RTP *between* two
  peer connections rather than from an external source.
