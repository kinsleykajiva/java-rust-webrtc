# Statistics

The library provides access to WebRTC connection statistics through the `StatsReport` class. Statistics are useful for monitoring connection quality, debugging issues, and building dashboards.

## Fetching Stats

```java
PeerConnection pc = PeerConnection.create(cfg, observer);
// ... after connection is established ...

StatsReport report = StatsReport.fetch(pc);
```

Stats are fetched synchronously. The Rust side queries the `RTCPeerConnection::get_stats()` method and returns a JSON string that Java parses.

## StatsReport Structure

A `StatsReport` contains three types of statistics:

### PeerConnectionStats

Top-level connection info:

```java
StatsReport.PeerConnectionStats pcStats = report.peerConnection();
pcStats.bytesReceived();    // total bytes received
pcStats.bytesSent();        // total bytes sent
pcStats.packetsReceived();  // total packets received
pcStats.packetsSent();      // total packets sent
pcStats.opened();           // data channels opened
pcStats.closed();           // data channels closed
```

### InboundRtpStats

Per-track receive statistics:

```java
for (StatsReport.InboundRtpStats rtp : report.inboundRtp()) {
    rtp.kind();              // "audio" or "video"
    rtp.codec();             // codec MIME type
    rtp.bytesReceived();     // bytes received on this track
    rtp.packetsReceived();   // packets received
    rtp.packetsLost();       // packets that didn't arrive
    rtp.jitter();            // jitter buffer estimate (seconds)
    rtp.ssrc();              // synchronization source ID
    rtp.trackId();           // remote track identifier
}
```

### RemoteCandidateStats

Per-candidate connection info:

```java
for (StatsReport.RemoteCandidateStats cand : report.remoteCandidates()) {
    cand.address();          // IP address
    cand.port();             // port number
    cand.protocol();         // "udp" or "tcp"
    cand.candidateType();    // "host", "srflx", or "relay"
    cand.bytesReceived();    // bytes received via this candidate
    cand.bytesSent();        // bytes sent via this candidate
    cand.rtt();              // round-trip time estimate (seconds)
}
```

## Reading InboundRtpStreamStats

The RTP stats include fields from `RTCRtpReceivedRtpStreamStats`:

| Field | Type | Description |
|-------|------|-------------|
| `jitter` | double | Jitter estimate in seconds |
| `packetsLost` | long | Number of packets lost |
| `packetsReceived` | long | Total packets received |
| `bytesReceived` | long | Total bytes received |
| `ssrc` | int | SSRC identifier |
| `codecId` | String | Codec identifier |
| `kind` | String | "audio" or "video" |
| `trackId` | String | Remote track identifier |

## Practical Example

```java
WebRtc.initialize();

Configuration cfg = Configuration.create();
PeerConnection pc = PeerConnection.create(cfg, observer);

// ... establish connection and exchange data ...

Thread.sleep(5000);  // let some data flow

StatsReport report = StatsReport.fetch(pc);

System.out.println("=== Connection Stats ===");
System.out.printf("Bytes sent:     %d%n", report.peerConnection().bytesSent());
System.out.printf("Bytes received: %d%n", report.peerConnection().bytesReceived());
System.out.printf("Data channels:  %d opened, %d closed%n",
                  report.peerConnection().opened(),
                  report.peerConnection().closed());

System.out.println("\n=== Inbound RTP ===");
for (StatsReport.InboundRtpStats rtp : report.inboundRtp()) {
    System.out.printf("  %s [%s]: %d packets (%d lost), jitter=%.4fs%n",
        rtp.kind(), rtp.codec(),
        rtp.packetsReceived(), rtp.packetsLost(), rtp.jitter());
}

System.out.println("\n=== Remote Candidates ===");
for (StatsReport.RemoteCandidateStats cand : report.remoteCandidates()) {
    System.out.printf("  %s:%d (%s/%s): RTT=%.3fs%n",
        cand.address(), cand.port(),
        cand.protocol(), cand.candidateType(),
        cand.rtt());
}

report.close();
pc.close();
```

## Running the StatsDemo

```bash
java --enable-native-access=ALL-UNNAMED \
     -cp "demo-code/target/classes;demo-code/target/dependency/*;." \
     io.github.kinsleykajiva.StatsDemo
```

The demo creates two peers, exchanges data over a data channel, and prints stats after the connection is established.

## JSON Format

The stats are returned from Rust as a JSON string. The Java parser handles these `RTCStatsReportEntry` variants:

- `PeerConnection` -- top-level connection stats
- `InboundRtp` -- inbound RTP stream stats
- `RemoteCandidate` -- remote ICE candidate stats

Additional variants may be added in future versions as the library's stats coverage expands.

## AutoCloseable

`StatsReport` implements `AutoCloseable`. Use try-with-resources:

```java
try (StatsReport report = StatsReport.fetch(pc)) {
    // use report
}
```
