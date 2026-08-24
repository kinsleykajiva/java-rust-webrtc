# Echo / SFU Demo Server

A self-contained, async **echo server** that demonstrates the JavaRust-WebRTC
library acting as a media server. A browser captures microphone + camera and
sends the streams over WebRTC to the Java backend; the backend loops the RTP
back to the same peer (an SFU-style *echo*), and the browser renders the
returned stream. This is the smallest meaningful building block for a
Janus-like selective-forwarding unit.

```text
 browser (getUserMedia)
   |  audio + video (sendrecv)
   |  WebSocket signaling (JSON)
   v
 Java EchoSession  -- PeerConnection
   |  onTrack  ->  TrackRemote.RtpCallback
   |  writeRtp ->  TrackLocal (echo)
   v
 browser renders its own media echoed back
```

## Run

Build a fat jar (native library is bundled for the current OS):

```bash
export JAVA_HOME=/Users/xuser/Library/Java/JavaVirtualMachines/liberica-full-25.0.3
export PATH="$JAVA_HOME/bin:$HOME/.cargo/bin:$HOME/.sdkman/candidates/maven/current/bin:$PATH"

mvn -pl demo-server -am package -Djextract.skip=true
java --enable-native-access=ALL-UNNAMED -jar demo-server/target/JavaRust-Webrtc-demo-server.jar
```

Then open <http://localhost:8080/echo-test.html>, click **Start**, and allow
camera/microphone. The "Echoed" pane shows your looped-back audio/video.

Options: `--http <port>` (default `8080`), `--ws <port>` (default `8081`).

## What you can test

- **Audio echo** — speak; you hear yourself back.
- **Video echo** — move; you see yourself back.
- **Mute audio** — toggles `audioTrack.enabled`; the echoed audio goes silent
  while the connection stays up.
- **Turn off video** — toggles `videoTrack.enabled`; the echoed video freezes
  to black while the connection stays up.

The server simply relays whatever RTP it receives, so client-side
enable/disable is all that is needed — no server renegotiation.

## Architecture

| Layer | Class | Responsibility |
|-------|-------|----------------|
| Entry | `Main` | Loads native engine, starts HTTP + WS servers, blocks. |
| HTTP | `Main.startHttp` | Serves `echo-test.html` from the jar. |
| WebSocket | `SignalingServer` | Per-connection lifecycle; dispatches messages on **virtual threads** (`Thread.startVirtualThread`) so the NIO read loop never blocks. |
| Session | `EchoSession` | Owns one `PeerConnection`; handles the JSON envelope; builds the echo tracks. |
| Echo | `EchoForwarder` | Forwards RTP from a received `TrackRemote` into the echo `TrackLocal` (no transcoding). |
| Helpers | `SdpCodecs`, `RtpPacket` | Negotiated codec discovery + RTP packet construction. |

The server is an **answerer**: the browser creates the offer (sendrecv
audio+video). On `offer`, the server:

1. Parses the offer to learn the client's audio/video codec (`SdpCodecs`).
2. Creates two `TrackLocal` RTP echo tracks with that exact codec.
3. `setRemoteDescription(offer)` → `createAnswer()` → `setLocalDescription(answer)`.
4. Reads the negotiated payload types from the answer and stamps loopback
   packets accordingly.
5. For each received track, `TrackRemote.setRtpCallback` writes the payloads
   verbatim into the matching echo track.

## Signaling protocol (JSON)

Every message uses one envelope, so request/response pairs and server events
share a single, predictable shape. This keeps the protocol maintainable — add
new actions without changing the wire format.

```json
{
  "version": "1.0",
  "type": "request" | "response" | "event",
  "action": "join | offer | answer | candidate | media-state | leave | ready | ack | bye | error | peer-connected | peer-disconnected",
  "session": "<server-assigned id>",
  "transaction": "<correlation id, echoed back for request/response>",
  "payload": { },
  "error": { "code": 4000, "message": "..." }
}
```

`error` is absent on success. Codes: `4000` = bad request / protocol error,
`5000` = internal error.

### Message flow

| Direction | action | payload | notes |
|-----------|--------|---------|-------|
| server → client | `ready` | `{session}` | sent on connect |
| client → server | `offer` | `{sdp}` | browser SDP offer |
| server → client | `answer` | `{sdp}` | server SDP answer |
| either → other | `candidate` | `{candidate, sdpMid, sdpMLineIndex}` | ICE, both ways |
| client → server | `media-state` | `{audio, video}` | mute/enable notification |
| client → server | `leave` | `{}` | teardown |
| server → client | `bye` | `{}` | session ended |
| server → client | `peer-connected` / `peer-disconnected` | `{}` | connection state events |
| either | `ack` / `error` | `{}` / `{error}` | generic outcomes |

## Notes / limitations

- This is a **demo echo**, not a full SFU. Each client is independent; media is
  looped only to the same peer. To build a real SFU you would fan the received
  RTP out to other peers (the `EchoForwarder` pattern extends directly to
  forwarding between connections, as in the `RtpForwarderDemo`).
- Transport uses the library default (UDP + TCP host candidates) plus a public
  STUN server, which is enough for localhost and same-LAN testing. Add TURN for
  NAT traversal in production.
- The fat jar bundles the native library for the OS it was built on. To run on
  another platform, build there or set `-Dwebrtc.native.lib=<path>`.
