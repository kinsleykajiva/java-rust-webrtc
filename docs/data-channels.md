# Data Channels

Data channels let you send arbitrary data between WebRTC peers -- strings, binary data, JSON messages, whatever you need. They work over the SCTP association established during the WebRTC handshake.

## Creating a Data Channel

Only the offerer (the peer that calls `createOffer`) can create data channels:

```java
int dcId = pc.createDataChannel("my-channel", true);
```

The first argument is the channel label (a human-readable name). The second argument determines whether the channel is ordered.

### Ordered vs Unordered

- **Ordered** (`true`): Messages arrive in the order they were sent. Slower, but predictable.
- **Unordered** (`false`): Messages may arrive out of order. Faster, good for real-time data where old messages don't matter.

## Setting Callbacks

After creating a channel (or receiving one from the remote peer), set up callbacks:

```java
pc.setDataChannelCallbacks(dcId,
    (id, data) -> {
        // Received a message
        String text = new String(data);
        System.out.println("Got: " + text);
    },
    id -> {
        // Channel is open and ready to send
        System.out.println("Channel open");
    },
    id -> {
        // Channel has closed
        System.out.println("Channel closed");
    }
);
```

The `MessageCallback` receives the channel ID and the raw byte array. The `StateCallback` receives just the channel ID for open/close events.

## Sending Data

Once the channel is open, send text or bytes:

```java
// Send a string
pc.sendDataChannelText(dcId, "Hello, world!");

// Send raw bytes
pc.sendDataChannelBytes(dcId, new byte[]{0x01, 0x02, 0x03});
```

Sending before the channel is open will fail silently or throw -- always wait for the `onOpen` callback.

## Receiving Data Channels

When the remote peer creates a data channel, the local peer receives it through the `onDataChannel` observer callback:

```java
@Override
public void onDataChannel(int id, String label) {
    System.out.println("Remote channel: " + label);

    pc.setDataChannelCallbacks(id,
        (chId, data) -> { /* handle message */ },
        chId -> { /* channel open */ },
        chId -> { /* channel closed */ }
    );
}
```

The channel is not usable until you set callbacks on it.

## Channel Lifecycle

```
createDataChannel()  ──>  CONNECTING  ──>  onOpen  ──>  OPEN
                                                           |
                                                    (messaging)
                                                           |
                                                     onClose  ──>  CLOSED
```

Once closed, a data channel cannot be reopened. Create a new one if needed.

## Ordering Guarantees

For ordered channels, the SCTP layer guarantees:
- Messages arrive in order
- No message is lost (SCTP retransmits)
- No duplicates

For unordered channels:
- Messages may arrive out of order
- No message is lost (unless the connection drops)
- No duplicates

## Practical Example

A complete data channel exchange between two peers on localhost:

```java
WebRtc.initialize();

Configuration cfg = Configuration.create();

// Offerer
PeerConnection offerer = PeerConnection.create(cfg, new PeerConnection.Observer() {
    @Override
    public void onDataChannel(int id, String label) {
        // This fires when the answerer creates a channel (we don't expect this here)
    }
    // ... other observer methods ...
});

// Answerer
PeerConnection answerer = PeerConnection.create(cfg, new PeerConnection.Observer() {
    @Override
    public void onDataChannel(int id, String label) {
        answerer.setDataChannelCallbacks(id,
            (chId, data) -> System.out.println("Answerer got: " + new String(data)),
            chId -> System.out.println("Answerer DC open"),
            chId -> System.out.println("Answerer DC closed"));
    }
    // ... other observer methods ...
});

// Offerer creates a channel
int dcId = offerer.createDataChannel("chat", true);
offerer.setDataChannelCallbacks(dcId,
    (id, data) -> System.out.println("Offerer got: " + new String(data)),
    id -> {
        System.out.println("Channel open, sending message");
        offerer.sendDataChannelText(dcId, "Hello from offerer!");
    },
    id -> System.out.println("Channel closed")
);

// SDP exchange
SessionDescription offer = offerer.createOffer();
offerer.setLocalDescription(offer);
answerer.setRemoteDescription(offer);

SessionDescription answer = answerer.createAnswer();
answerer.setLocalDescription(answer);
offerer.setRemoteDescription(answer);

// Wait for connection and messaging...
```

See the `Main` demo class for a complete working example.
