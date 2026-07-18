# Media Tracks

This document covers sending and receiving audio and video through the WebRTC library.

## Overview

Media in WebRTC flows through tracks. A **local track** (`TrackLocal`) is something you send to the remote peer. A **remote track** (`TrackRemote`) is something the remote peer sends to you.

Each track has a kind (audio or video), a codec, and an SSRC (synchronization source identifier) that identifies it in RTP packets.

## TrackLocal

There are two ways to create a local track, depending on how you produce the media data.

### Sample-based tracks

Use sample tracks when you have decoded media data (raw audio samples, raw video frames). The library handles RTP packetization for you.

```java
TrackLocal track = TrackLocal.create(
    MediaKind.VIDEO,           // kind
    "stream-id",               // stream identifier
    "video-track",             // track identifier
    "Camera",                  // display label
    TrackLocal.randomSsrc(),   // SSRC
    MimeTypes.VIDEO_VP8,       // codec MIME type
    90000,                     // clock rate (90kHz for video)
    0,                         // channels (0 for video)
    "profile-level-id=42e01f"  // SDP fmtp line
);
```

Writing samples:

```java
byte[] frame = readNextFrame();  // your frame data
track.writeSample(payloadType, frame, 33);  // 33ms duration
```

### RTP-based tracks

Use RTP tracks when you have complete RTP packets (e.g., from an RTP source, an RTP-to-WebRTC bridge, or a custom packetizer).

```java
TrackLocal track = TrackLocal.createRtpTrack(
    MediaKind.VIDEO,
    "stream-id",
    "rtp-track",
    "RTP Source",
    TrackLocal.randomSsrc(),
    MimeTypes.VIDEO_VP8,
    90000
);
```

Writing RTP packets:

```java
byte[] rtpPacket = readRtpPacket();  // complete RTP packet (header + payload)
track.writeRtp(rtpPacket);
```

The library rewrites the SSRC in the packet to match the one specified during track creation.

### Adding tracks to a peer connection

```java
int senderId = pc.addTrack(track);
```

This returns a sender ID that you can use later to query codec info or remove the track:

```java
int pt = pc.senderGetPayloadType(senderId);
String codec = pc.senderGetCodec(senderId);
pc.removeTrack(senderId);
```

## TrackRemote

Remote tracks are registered when the remote peer adds them. The library fires an `onTrack` callback on the `PeerConnection.Observer` (through the Forwarder), and the track is stored in a global registry keyed by a native ID.

### Receiving RTP packets

```java
TrackRemote remote = TrackRemote.get(nativeTrackId);

remote.register(
    (trackId, payload, payloadType, seq, timestamp, ssrc) -> {
        // Handle incoming RTP packet
        System.out.printf("RTP: pt=%d seq=%d ts=%d ssrc=%d len=%d%n",
                          payloadType, seq, timestamp, ssrc, payload.length);
    },
    (trackId, ssrc, rid) -> {
        System.out.printf("Track opened: ssrc=%d rid=%s%n", ssrc, rid);
    }
);
```

### Track info

```java
remote.getSsrcs();     // array of SSRC values
remote.getCodec();     // tab-separated codec info
remote.getKind();      // 1=audio, 2=video
remote.getRid();       // RTP Stream ID (for simulcast)
remote.getTrackId();   // track identifier
remote.getLabel();     // display label
```

### Requesting keyframes

If you're receiving video and need a keyframe (e.g., after packet loss), send a PLI:

```java
remote.writeRtcpPli();
```

## Transceivers

Transceivers control the direction of media flow (send, receive, or both).

```java
int transceiverId = pc.addTransceiverFromKind(
    MediaKind.VIDEO,
    TransceiverDirection.SEND_RECV
);
```

Direction constants:

| Value | Constant | Meaning |
|-------|----------|---------|
| 0 | UNSPECIFIED | Let the stack decide |
| 1 | SEND_RECV | Send and receive |
| 2 | SEND_ONLY | Send only |
| 3 | RECV_ONLY | Receive only |
| 4 | INACTIVE | Disabled |

## Codecs

The library registers 25 codecs by default. Query them:

```java
List<Codec> codecs = WebRtc.listSupportedCodecs();
for (Codec c : codecs) {
    System.out.printf("%s %dHz %s%n", c.mimeType(), c.clockRate(),
                      c.sdpFmtpLine());
}
```

Key audio codecs:
- Opus (48 kHz, wideband) -- best quality, used by default
- G.722 (8 kHz, wideband telephony)
- PCMU (G.711 mu-law, 8 kHz)
- PCMA (G.711 A-law, 8 kHz)

Key video codecs:
- VP8, VP9 (royalty-free)
- H.264, H.265 (widely deployed, patent-encumbered)
- AV1 (next-generation, royalty-free)
- RTX (retransmission), ULP-FEC, Flex-FEC (error correction)

## MIME Type Constants

Use the `MimeTypes` class for codec strings:

```java
String mimeType = MimeTypes.VIDEO_H264;    // "video/H264"
String audioType = MimeTypes.AUDIO_OPUS;   // "audio/opus"
```

## Content Files

The `MimeTypes` class also provides paths to test media files in `demo-content/`:

```java
String videoPath = MimeTypes.CONTENT_VP8;   // "demo-content/output_vp8.ivf"
String audioPath = MimeTypes.CONTENT_OPUS;  // "demo-content/output.ogg"
```

These files are generated with ffmpeg and used by the play-from-disk demos. See [Play from Disk](play-from-disk.md) for details.

## RTP Packet Structure

When using RTP tracks, the byte array passed to `writeRtp()` must be a complete RTP packet:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|V=2|P|X|  CC   |M|     PT      |       sequence number         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           timestamp                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|           synchronization source (SSRC) identifier            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|            contributing source (CSRC) identifiers             |
|                             ....                              |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          payload data                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

The library rewrites the SSRC field to match the track's configured SSRC before forwarding.
