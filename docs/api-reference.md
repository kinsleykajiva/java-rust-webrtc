# API Reference

This document covers the main classes in the `io.github.kinsleykajiva.webrtc` package.

## WebRtc

Library initialization and utility methods.

```java
public final class WebRtc {
    /** Initialize the native library. Must be called once before any other API. */
    public static void initialize();

    /** List all codecs supported by the media engine. */
    public static List<Codec> listSupportedCodecs();
}
```

Always call `WebRtc.initialize()` at the start of your application. It loads the native DLL and prepares the FFM bindings.

## Configuration

Builds the settings for a peer connection. Implements `AutoCloseable`.

```java
public final class Configuration implements AutoCloseable {
    public static Configuration create();

    /** Add a STUN server (URL only). */
    public Configuration addIceServer(String urls);

    /** Add a TURN server with credentials. */
    public Configuration addIceServer(String urls, String username, String credential);

    /** Set UDP addresses, TCP addresses, DTLS role, and network types in one call. */
    public Configuration setTransport(List<String> udpAddrs, List<String> tcpAddrs,
                                      int dtlsRole, int networkTypes);

    /** TCP-only mode with specified address and DTLS role. */
    public Configuration useTcpOnly(String addr, DtlsRole role);

    /** Filter to specific network types (bitmask: 1=UDP, 2=TCP). */
    public Configuration useNetworkTypes(int networkTypes);

    /** Set the UDP/TCP port range for candidate gathering. */
    public Configuration setPortRange(int minPort, int maxPort);

    /** Set port allocator flags (bitmask of PortAllocatorFlags constants). */
    public Configuration setAllocatorFlags(int flags);

    public void close();
}
```

### Example

```java
Configuration cfg = Configuration.create();
cfg.addIceServer("stun:stun.l.google.com:19302");
cfg.setPortRange(10000, 20000);
cfg.setAllocatorFlags(PortAllocatorFlags.DISABLE_RELAY);
```

## PeerConnection

The central class for WebRTC communication. Implements `AutoCloseable`.

```java
public final class PeerConnection implements AutoCloseable {
    /** Create a peer connection with a configuration and observer. */
    public static PeerConnection create(Configuration cfg, Observer observer);

    /** Create an SDP offer (optionally with ICE restart). */
    public SessionDescription createOffer();
    public SessionDescription createOffer(boolean iceRestart);

    /** Create an SDP answer. */
    public SessionDescription createAnswer();

    /** Set the local or remote description. */
    public void setLocalDescription(SessionDescription sdp);
    public void setRemoteDescription(SessionDescription sdp);

    /** Add a remote ICE candidate received from the signaling channel. */
    public void addIceCandidate(String candidate, String sdpMid, int sdpMLineIndex);

    /** Create a reliable data channel. */
    public int createDataChannel(String label, boolean ordered);

    /** Set callbacks on a data channel. */
    public void setDataChannelCallbacks(int id, DataChannel.MessageCallback onMessage,
                                         DataChannel.StateCallback onOpen,
                                         DataChannel.StateCallback onClose);

    /** Send text through a data channel. */
    public void sendDataChannelText(int id, String text);

    /** Send bytes through a data channel. */
    public void sendDataChannelBytes(int id, byte[] data);

    /** Add a local track (sample-based or RTP). Returns sender ID. */
    public int addTrack(TrackLocal track);

    /** Remove a track by sender ID. */
    public void removeTrack(int senderId);

    /** Get all sender IDs. */
    public List<Integer> getSenders();

    /** Get the negotiated payload type for a sender. */
    public int senderGetPayloadType(int senderId);

    /** Get codec info for a sender (tab-separated: codec, clockRate, channels, fmtp). */
    public String senderGetCodec(int senderId);

    /** Add a transceiver (from kind: audio/video). */
    public int addTransceiverFromKind(MediaKind kind, TransceiverDirection direction);

    /** Fetch connection statistics. */
    public StatsReport getStats();

    /** Close the peer connection and release resources. */
    public void close();

    /** Observer interface for receiving events. */
    public interface Observer {
        void onIceCandidate(String candidate, String sdpMid);
        void onIceGatheringStateChange(IceGatheringState state);
        void onConnectionStateChange(PeerConnectionState state);
        void onDataChannel(int id, String label);
        // onTrack is available but requires TrackRemote registration
    }
}
```

### Example

```java
WebRtc.initialize();

Configuration cfg = Configuration.create();
PeerConnection pc = PeerConnection.create(cfg, new PeerConnection.Observer() {
    @Override
    public void onIceCandidate(String candidate, String sdpMid) {
        // Send candidate to remote peer via signaling
    }

    @Override
    public void onConnectionStateChange(PeerConnectionState state) {
        System.out.println("State: " + state);
    }

    @Override
    public void onDataChannel(int id, String label) {
        System.out.println("Remote data channel: " + label);
    }

    @Override
    public void onIceGatheringStateChange(IceGatheringState state) {
        System.out.println("Gathering: " + state);
    }
});

int dcId = pc.createDataChannel("my-channel", true);
pc.setDataChannelCallbacks(dcId,
    (id, data) -> System.out.println("Received: " + new String(data)),
    id -> System.out.println("DC open"),
    id -> System.out.println("DC closed"));

SessionDescription offer = pc.createOffer();
pc.setLocalDescription(offer);
// ... exchange offer/answer and ICE candidates ...
pc.close();
```

## SessionDescription

Wraps an SDP offer or answer.

```java
public record SessionDescription(SdpType type, String sdp) implements AutoCloseable {
    public void close();
}
```

Created by `PeerConnection.createOffer()` or `createAnswer()`. The same handle can be reused across peers -- the Rust side clones the SDP internally.

## DataChannel

Constants and callback interfaces for data channels.

```java
public final class DataChannel {
    public static final int SCTP_TRANSPORT_OPEN = 1;
    public static final int SCTP_TRANSPORT_CLOSED = 3;

    public interface MessageCallback {
        void onMessage(int channelId, byte[] data);
    }

    public interface StateCallback {
        void onStateChange(int channelId);
    }
}
```

## TrackLocal

Represents a local media track to send to the remote peer. Two creation modes:

```java
public final class TrackLocal implements AutoCloseable {
    /** Create a sample-based track (for file playback). */
    public static TrackLocal create(MediaKind kind, String streamId, String trackId,
                                     String label, long ssrc, String mimeType,
                                     int clockRate, int channels, String fmtp);

    /** Create an RTP-based track (for raw RTP forwarding). */
    public static TrackLocal createRtpTrack(MediaKind kind, String streamId,
                                             String trackId, String label,
                                             long ssrc, String mimeType,
                                             int clockRate);

    /** Write a sample payload (for sample-based tracks). */
    public void writeSample(int payloadType, byte[] data, long durationMs);

    /** Write a raw RTP packet (for RTP tracks). */
    public void writeRtp(byte[] data);

    /** Generate a random SSRC for use in track creation. */
    public static long randomSsrc();

    public void close();
}
```

### Sample tracks vs RTP tracks

- **Sample tracks** use `writeSample()`. You provide the payload, payload type, and duration. The library handles RTP packetization internally.
- **RTP tracks** use `writeRtp()`. You provide a complete RTP packet (header + payload). The library rewrites the SSRC to match the one used during creation.

## TrackRemote

Represents a remote media track received from the peer.

```java
public final class TrackRemote {
    /** Get a registered track by its native ID (from onTrack callback). */
    public static TrackRemote get(int nativeTrackId);

    /** Register callbacks for RTP packets and track open event. */
    public void register(RtpCallback onRtp, OpenCallback onOpen);

    public int[] getSsrcs();
    public String getCodec();     // tab-separated: mimeType, clockRate, channels, fmtp
    public int getKind();         // 1=audio, 2=video
    public String getRid();
    public int getTrackId();
    public String getLabel();

    /** Send a PLI (Picture Loss Indication) to request a keyframe. */
    public void writeRtcpPli();

    public interface RtpCallback {
        void onRtp(int trackId, byte[] payload, int payloadType,
                   int seq, int timestamp, int ssrc);
    }

    public interface OpenCallback {
        void onOpen(int trackId, int ssrc, String rid);
    }
}
```

## Enums

### PeerConnectionState

```
NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED
```

### IceGatheringState

```
NEW, GATHERING, COMPLETE
```

### SdpType

```
OFFER, PRANSWER, ANSWER, ROLLBACK
```

### MediaKind

```
AUDIO (1), VIDEO (2)
```

### DtlsRole

```
UNSPECIFIED (0), AUTO (1), CLIENT (2), SERVER (3)
```

### TransceiverDirection

```
UNSPECIFIED (0), SEND_RECV (1), SEND_ONLY (2), RECV_ONLY (3), INACTIVE (4)
```

### NetworkType

```
UDP (1), TCP (2)
```

## Constants

### MimeTypes

Audio codec MIME types:
- `AUDIO_OPUS` = `"audio/opus"` (48 kHz)
- `AUDIO_G722` = `"audio/g722"` (8 kHz)
- `AUDIO_PCMU` = `"audio/PCMU"` (8 kHz, G.711 mu-law)
- `AUDIO_PCMA` = `"audio/PCMA"` (8 kHz, G.711 A-law)
- `AUDIO_TELEPHONE_EVENT` = `"audio/telephone-event"` (DTMF)

Video codec MIME types:
- `VIDEO_VP8` = `"video/VP8"`
- `VIDEO_VP9` = `"video/VP9"`
- `VIDEO_H264` = `"video/H264"`
- `VIDEO_H265` = `"video/H265"`
- `VIDEO_AV1` = `"video/AV1"`
- `VIDEO_RTX` = `"video/rtx"` (retransmission)
- `VIDEO_ULP_FEC` = `"video/ulpfec"` (flexible FEC)
- `VIDEO_FLEX_FEC` = `"video/flexfec"`
- `VIDEO_FLEX_FEC03` = `"video/x-ulpfec03"`

Content file paths (for demos):
- `CONTENT_VP8`, `CONTENT_VP9`, `CONTENT_H264`, `CONTENT_H265`
- `CONTENT_OPUS`, `CONTENT_PLAYLIST`

### PortAllocatorFlags

Bitmask constants for candidate gathering control. See [Port Allocator](port-allocator.md) for details.

### Codec

```java
public record Codec(String mimeType, int clockRate, int channels,
                    String sdpFmtpLine, String rtcpFeedback) {}
```

Created by `WebRtc.listSupportedCodecs()`. Describes a single codec registered in the media engine.

## StatsReport

```java
public final class StatsReport implements AutoCloseable {
    /** Fetch statistics for a peer connection. */
    public static StatsReport fetch(PeerConnection pc);

    public List<InboundRtpStats> inboundRtp();
    public PeerConnectionStats peerConnection();
    public List<RemoteCandidateStats> remoteCandidates();
    public void close();
}
```

See [Statistics](stats.md) for field descriptions.
