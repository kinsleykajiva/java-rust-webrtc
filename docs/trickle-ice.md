# Trickle ICE

Trickle ICE is the process of sending ICE candidates to the remote peer as they are discovered, rather than waiting for all candidates to be gathered before starting the SDP exchange.

## Why Trickle ICE?

Without trickle ICE, the peer waits until all candidates (host, srflx, relay) are gathered, embeds them in the SDP offer/answer, and then sends it. This can take several seconds, especially when TURN servers are involved.

With trickle ICE, the SDP is sent immediately (without candidates), and each candidate is sent separately as it's discovered. The remote peer adds each candidate as it arrives. This dramatically reduces connection setup time.

## How it Works

```
Offerer                             Answerer
   |                                   |
   |-- createOffer() ----------------->|   SDP has no candidates
   |-- setLocalDescription() --------->|
   |                                   |
   |<-- setRemoteDescription() --------|
   |<-- createAnswer() ---------------|
   |<-- setLocalDescription() --------|
   |                                   |
   |  (ICE gathering starts)           |  (ICE gathering starts)
   |                                   |
   |<-- onIceCandidate(Host1) --------|   candidate trickled
   |-- addIceCandidate(Host1) ------->|
   |                                   |
   |-- onIceCandidate(Host2) -------->|   candidate trickled
   |<-- addIceCandidate(Host2) -------|
   |                                   |
   |<-- onIceCandidate(srflx) --------|   STUN responded
   |-- addIceCandidate(srflx) ------->|
   |                                   |
   |  (Gathering complete)             |  (Gathering complete)
   |                                   |
   |<==== Connectivity checks =======>|
   |<==== Data channel open =========>|
```

## In the Library

The library uses trickle ICE by default. When you call `createOffer()` or `createAnswer()`, the SDP does not contain candidates. Candidates arrive through the `onIceCandidate` callback as the ICE agent discovers them.

```java
PeerConnection pc = PeerConnection.create(cfg, new PeerConnection.Observer() {
    @Override
    public void onIceCandidate(String candidate, String sdpMid) {
        // Send this to the remote peer via your signaling channel
        signaling.send("candidate", candidate, sdpMid);
    }

    @Override
    public void onIceGatheringStateChange(IceGatheringState state) {
        switch (state) {
            case NEW -> System.out.println("Not started");
            case GATHERING -> System.out.println("Collecting candidates...");
            case COMPLETE -> System.out.println("All candidates gathered");
        }
    }
});

// Create and send offer immediately
SessionDescription offer = pc.createOffer();
pc.setLocalDescription(offer);
sendToRemotePeer(offer);  // SDP without candidates

// When remote peer sends candidates back:
pc.addIceCandidate(candidate, sdpMid, sdpMLineIndex);
```

## ICE Gathering States

The gathering state transitions through three phases:

1. **NEW** -- Initial state, gathering has not started.
2. **GATHERING** -- The ICE agent is actively collecting candidates. This includes host candidates (fast), STUN candidates (depends on server), and TURN candidates (slowest).
3. **COMPLETE** -- All candidates have been gathered. No more `onIceCandidate` callbacks will fire.

```
NEW  ──[setLocalDescription]──>  GATHERING  ──[all found]──>  COMPLETE
```

## Candidate Timing

Different candidate types arrive at different times:

- **Host candidates**: Nearly instant (local interface enumeration)
- **STUN candidates**: ~100-500ms (RTT to STUN server)
- **TURN candidates**: ~500-2000ms (TURN allocation + permissions)

This is why trickle ICE matters -- without it, you'd wait for the TURN allocation before sending anything.

## TrickleIceDemo

The `TrickleIceDemo` in `demo-code/` demonstrates the full trickle flow with five modes:

```bash
# Host only (fastest, loopback only)
java ... TrickleIceDemo host

# With STUN server
java ... TrickleIceDemo srflx

# With TURN server
java ... TrickleIceDemo relay

# Both STUN and TURN
java ... TrickleIceDemo combined

# Port range + flags
java ... TrickleIceDemo flags
```

The demo prints each candidate as it arrives, showing the trickle behavior in real time.

## Non-Trickle Usage

If you need the old-style non-trickle flow (all candidates embedded in SDP), you can wait for gathering to complete before sending the offer:

```java
SessionDescription offer = pc.createOffer();
pc.setLocalDescription(offer);

// Wait for gathering to complete
while (gatheringState.get() != IceGatheringState.COMPLETE) {
    Thread.sleep(100);
}

// Now the local description contains all candidates
// Retrieve it and send to remote peer
```

Note: the library does not provide a direct way to retrieve the updated local description with candidates. The current design relies on trickle ICE where candidates are sent separately.
