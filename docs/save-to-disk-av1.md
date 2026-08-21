# Save Received Media to Disk (AV1)

This is the inverse of [Play from Disk](play-from-disk.md): instead of reading a media file and
* sending it, you *receive* RTP from a remote peer, depacketize it back into a raw bitstream, and
write it to a container file. This guide focuses on **AV1**, since it is the one codec that needs
non-trivial RTP depacketization before it can be stored.

The `save-to-disk-av1` example (mirroring the webrtc-rs `save-to-disk-av1` Rust example) receives
AV1 RTP, strips the RTP aggregation header, reassembles the OBU stream, and writes each frame as an
IVF entry.

## AV1 RTP depacketization

AV1 is carried in RTP as OBUs (Open Bitstream Units) prefixed by a 1-byte **aggregation header**:

```
 0 1 2 3 4 5 6 7
+-+-+-+-+-+-+-+-+
|Z|Y|W|N|-|-|-|-|   Z=first packet of frame, Y=last, W=obu count, N=don't aggregate
+-+-+-+-+-+-+-+-+
```

`Av1Depacketizer` is a straight Java port of `webrtc-rs::av1::depacketizer::Av1Depacketizer`. It:

1. Reads the aggregation header to learn whether this packet is the start (`Z`), end (`Y`), or a
   fragment of a frame, and how many OBUs it carries (`W`).
2. Strips the header and walks the OBUs. Per the AV1 RTP spec, when an OBU has no size field the
   size is implied by the remaining payload; the depacketizer re-inserts `obu_size` fields so the
   bytes form a valid **low-overhead AV1 bitstream** (what an IVF muxer / decoder expects).
3. Buffers fragments across packets until `Y` closes the frame, then returns the complete frame.

```java
Av1Depacketizer depacketizer = new Av1Depacketizer();
byte[] frame = depacketizer.depacketize(rtpPayload);  // null/partial until frame complete
if (frame != null) {
    ivf.writeFrame(frame, timestamp);
}
```

A temporal-delimiter OBU (type 2) is inserted at the start of each frame so decoders can resync.

## Writing an IVF file

`IvfWriter` is a minimal IVF (Indeo Video Format) muxer. It writes the 32-byte `DKIF` header
(`AV01` fourcc for AV1) followed by one entry per frame: a 4-byte size, an 8-byte timestamp, then
the frame bytes. This matches the Rust example's `IVFWriter::write_rtp`, which stores each RTP
payload verbatim as an IVF frame.

```java
try (IvfWriter ivf = new IvfWriter(
        new FileOutputStream("output_av1_saved.ivf"), 320, 240, 1, 90_000)) {
    // ... for each completed AV1 frame: ivf.writeFrame(frame, timestamp);
}
```

## The demo flow

`SaveToDiskAv1Demo` runs two peer connections on loopback (TCP transport, answerer on port `8452`):

1. **Receiver** adds a `recvonly` AV1 transceiver and, on each RTP packet, depacketizes the payload
   and appends it to the IVF file.
2. **Sender** publishes a synthetic AV1 stream: it builds RTP packets whose payloads already include
   the AV1 RTP aggregation header and proper OBU framing, then calls `track.writeRtp(packet)`.
3. After ~60 frames the receiver closes the IVF file.

```java
receiver.addTransceiver(MediaKind.VIDEO, TransceiverDirection.RECV_ONLY);
// onTrack -> setRtpCallback:
byte[] frame = depacketizer.depacketize(payload);
if (frame != null) ivf.writeFrame(frame, timestamp);
```

## Verifying the output

The saved `output_av1_saved.ivf` is a valid AV1 IVF. You can probe and decode it with ffmpeg:

```bash
ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height \
        -of default=noprint_wrappers=1 output_av1_saved.ivf

ffmpeg -i output_av1_saved.ivf -frames:v 1 first_frame.png   # decode one frame
```

If ffmpeg reports `codec_name=av1` and decodes a frame, the depacketizer round-trip is correct.

## Running the demo

```bash
java --enable-native-access=ALL-UNNAMED \
     -Dwebrtc.native.lib=rust-webrtc-ffi/target/release/librust_webrtc_ffi.dylib \
     -cp "demo-code/target/classes:library/target/classes:<slf4j>:<logback>" \
     io.github.kinsleykajiva.SaveToDiskAv1Demo
```

Expected output:

```
Frames saved:   60
SUCCESS: AV1 RTP saved to output_av1_saved.ivf
```

## Notes

- This pattern generalizes to any codec: for VP8/VP9/H.26x the per-packet payload is already a
  decodable frame (or NAL unit), so you can write it straight to an IVF / Annex-B file without a
  depacketizer. AV1 is the interesting case because frames may be fragmented across RTP packets.
- The IVF `timebase` should match the codec clock rate (90 000 for video). The `width`/`height`
  fields in the IVF header are informational; AV1 carries its own dimensions.
- See [Media Tracks](media-tracks.md) for the `TrackRemote` RTP callback API used to receive the
  packets.
