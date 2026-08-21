# Simulcast

Simulcast means sending the **same source encoded at multiple quality levels** (a.k.a. *layers*)
so the receiver can pick whichever layer it can handle. webrtc-rs expresses this as several
encodings on a single transceiver, each identified by a **RID** (`q` = quarter/resolution,
`h` = half, `f` = full). This library supports two ways to publish a simulcast source from Java.

## Approach A — one `TrackLocal` per layer

The simplest mapping: add a separate `TrackLocal` RTP track for each layer, each with its own
SSRC and the same codec. The SDP then carries an `a=ssrc-group:SIM` line tying the SSRCs together.

```java
// Sender: three separate video tracks, one per simulcast layer.
Map<String, TrackLocal> tracks = new LinkedHashMap<>();
for (String rid : new String[]{"q", "h", "f"}) {
    int ssrc = TrackLocal.randomSsrc();
    TrackLocal track = TrackLocal.createRtpTrack(
        MediaKind.VIDEO, "stream_" + rid, "video_" + rid, "video_" + rid,
        ssrc, "video/VP8", 90_000);
    sender.addTrack(track);
    tracks.put(rid, track);
}
```

The receiver adds a single `recvonly` video transceiver and observes **three independent tracks**
(one per layer) via `onTrack` + a `RtpCallback`:

```java
receiver.addTransceiver(MediaKind.VIDEO, TransceiverDirection.RECV_ONLY);
// ...
void onTrack(int trackId, String label) {
    TrackRemote t = TrackRemote.get(trackId);
    t.setRtpCallback((id, payload, pt, seq, ts, ssrc) -> {
        // count packets per layer
    });
}
```

This is exactly what `SimulcastDemo` does.

## Approach B — `addTransceiverFromKindWithEncoding`

This is a closer port of the webrtc-rs `simulcast_add_transceiver_from_kind` example. Instead of
adding tracks after the fact, you add a **send-only transceiver that already declares one encoding
with an explicit `rid`, `ssrc`, codec, clock rate, and channel count**. The library maps directly
onto webrtc-rs `add_transceiver_from_kind(..., Some(RTCRtpTransceiverInit { send_encodings }))`.

```java
// Sender: one send-only transceiver per layer, fully described up front.
for (String rid : new String[]{"q", "h", "f"}) {
    int ssrc = TrackLocal.randomSsrc();
    sender.addTransceiverFromKindWithEncoding(
        MediaKind.VIDEO, TransceiverDirection.SEND_ONLY,
        rid, ssrc, "video/VP8", 90_000, 0);

    TrackLocal track = TrackLocal.createRtpTrack(
        MediaKind.VIDEO, "stream_" + rid, "video_" + rid, "video_" + rid,
        ssrc, "video/VP8", 90_000);
    sender.addTrack(track);
}
```

The FFI function for this is `webrtc_ffi_add_transceiver_from_kind_with_encoding`, and the Java
wrapper is:

```java
public void PeerConnection.addTransceiverFromKindWithEncoding(
    MediaKind kind, TransceiverDirection direction,
    String rid, long ssrc, String mimeType, long clockRate, int channels)
```

Pass `ssrc == 0` to let the engine pick one. The negotiated SDP contains `a=rid:` lines plus
`a=ssrc-group:FID` / `a=ssrc-group:SIM` groups so a conformant receiver can demultiplex the layers.

`SimulcastAddTransceiverFromKindDemo` uses Approach B.

## Receiving simulcast

A `recvonly` video transceiver is enough on the receiver side. webrtc-rs opens one `TrackRemote`
per encoding, and each reports its RID through `TrackRemote.getRid()`. Observe them:

```java
TrackRemote t = TrackRemote.get(trackId);
System.out.printf("layer rid=%s ssrcs=%s%n", t.getRid(), Arrays.toString(t.getSsrcs()));
```

## Running the demos

Both demos use TCP loopback transport (`127.0.0.1`) because a UDP-only configuration does not
gather usable candidates on loopback. `SimulcastDemo` uses port `8451`;
`SimulcastAddTransceiverFromKindDemo` uses port `8450`.

```bash
java --enable-native-access=ALL-UNNAMED \
     -Dwebrtc.native.lib=rust-webrtc-ffi/target/release/librust_webrtc_ffi.dylib \
     -cp "demo-code/target/classes:library/target/classes:<slf4j>:<logback>" \
     io.github.kinsleykajiva.SimulcastDemo
```

Expected output (both demos):

```
Connected. Streaming 3 simulcast layers...
Layers received:    3
Total RTP received: 150
SUCCESS: simulcast published and all 3 layers received.
```

## Notes

- The two approaches are equivalent on the wire; Approach B gives you explicit control over the
  `rid`/`ssrc` declared in the offer, which some SFUs expect.
- Simulcast only makes sense for **send-only** or **send-recv** directions. A `recvonly`
  transceiver on the sender side produces no encodings.
- All layers must use the same codec and clock rate to be grouped as simulcast.
