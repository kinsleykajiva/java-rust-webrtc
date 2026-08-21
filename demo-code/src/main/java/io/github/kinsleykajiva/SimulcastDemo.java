package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.DtlsRole;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.NetworkType;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.TrackRemote;
import io.github.kinsleykajiva.webrtc.TransceiverDirection;
import io.github.kinsleykajiva.webrtc.WebRtc;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mirrors the Rust {@code simulcast} example. The sender publishes a video simulcast by adding
 * three separate {@link TrackLocal} video tracks ({@code q} / {@code h} / {@code f}), each with its
 * own SSRC and a {@code video/VP8} codec. A single receiver adds a recvonly video transceiver and
 * should observe three independent tracks (one per simulcast layer).
 */
public final class SimulcastDemo {

    private static final int VIDEO_CLOCK_RATE = 90_000;
    private static final int VP8_PAYLOAD_TYPE = 96;
    private static final String[] RIDS = {"q", "h", "f"};
    private static final int PACKETS_PER_LAYER = 50;

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        CountDownLatch connected = new CountDownLatch(2);
        final PeerConnection[] sender = {null};
        final PeerConnection[] receiver = {null};

        final AtomicInteger receivedTracks = new AtomicInteger(0);
        final ConcurrentHashMap<String, AtomicInteger> packetsPerLayer = new ConcurrentHashMap<>();
        final AtomicInteger totalReceived = new AtomicInteger(0);

        // ---- RECEIVER (answerer): recvonly ----
        receiver[0] = PeerConnection.create(tcpAnswerer(8451), new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (sender[0] != null) sender[0].addIceCandidate(candidate, sdpMid, 0);
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[receiver] " + state);
                if (state == PeerConnectionState.CONNECTED) connected.countDown();
            }

            @Override
            public void onTrack(int trackId, String label) {
                int n = receivedTracks.incrementAndGet();
                System.out.println("[receiver] track #" + n + " opened (id=" + trackId + ")");
                TrackRemote t = TrackRemote.get(trackId);
                String key = "track-" + trackId;
                packetsPerLayer.computeIfAbsent(key, k -> new AtomicInteger());
                t.setRtpCallback((id, payload, pt, seq, ts, ssrc) -> {
                    packetsPerLayer.get(key).incrementAndGet();
                    totalReceived.incrementAndGet();
                });
            }
        });
        receiver[0].addTransceiver(MediaKind.VIDEO, TransceiverDirection.RECV_ONLY);

        // ---- SENDER (offerer): three separate video tracks ----
        sender[0] = PeerConnection.create(tcpOfferer(), new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (receiver[0] != null) receiver[0].addIceCandidate(candidate, sdpMid, 0);
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[sender] " + state);
                if (state == PeerConnectionState.CONNECTED) connected.countDown();
            }
        });

        Map<String, TrackLocal> tracks = new LinkedHashMap<>();
        for (String rid : RIDS) {
            int ssrc = TrackLocal.randomSsrc();
            TrackLocal track = TrackLocal.createRtpTrack(
                    MediaKind.VIDEO, "stream_" + rid, "video_" + rid, "video_" + rid, ssrc, "video/VP8", VIDEO_CLOCK_RATE);
            sender[0].addTrack(track);
            tracks.put(rid, track);
        }

        // ---- Signaling ----
        SessionDescription offer = sender[0].createOffer();
        sender[0].setLocalDescription(offer);
        receiver[0].setRemoteDescription(offer);
        SessionDescription answer = receiver[0].createAnswer();
        receiver[0].setLocalDescription(answer);
        sender[0].setRemoteDescription(answer);

        System.out.println("Waiting for connection...");
        if (!connected.await(20, TimeUnit.SECONDS)) {
            System.err.println("Connection timeout");
            shutdown(sender[0], receiver[0]);
            return;
        }
        System.out.println("Connected. Streaming " + RIDS.length + " simulcast layers...");

        int expected = RIDS.length * PACKETS_PER_LAYER;
        for (String rid : RIDS) {
            final TrackLocal track = tracks.get(rid);
            final int layerSsrc = track.hashCode() & 0x7FFFFFFF | 0x1;
            Thread t = new Thread(() -> {
                for (int seq = 1; seq <= PACKETS_PER_LAYER; seq++) {
                    byte[] payload = new byte[]{0x10, (byte) rid.charAt(0), (byte) seq, 0x00};
                    byte[] packet = buildRtpPacket(layerSsrc, VP8_PAYLOAD_TYPE, seq, seq * 3000, payload);
                    try {
                        track.writeRtp(packet);
                    } catch (Exception e) {
                        System.err.println("[sender:" + rid + "] write error: " + e.getMessage());
                    }
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "sender-" + rid);
            t.start();
        }

        Thread.sleep(3000);

        System.out.println("Layers received:    " + receivedTracks.get());
        System.out.println("Total RTP received: " + totalReceived.get());
        for (String rid : RIDS) {
            System.out.println("  layer '" + rid + "': published");
        }
        System.out.println("  per received track: " + packetsPerLayer.size() + " tracks with RTP");
        for (Map.Entry<String, AtomicInteger> e : packetsPerLayer.entrySet()) {
            System.out.println("    " + e.getKey() + ": " + e.getValue().get() + " packets");
        }
        if (receivedTracks.get() == RIDS.length && totalReceived.get() == expected) {
            System.out.println("SUCCESS: simulcast published and all " + RIDS.length + " layers received.");
        } else {
            System.out.println("FAILURE: expected " + RIDS.length + " layers / " + expected + " packets.");
        }

        shutdown(sender[0], receiver[0]);
    }

    private static byte[] buildRtpPacket(int ssrc, int payloadType, int sequenceNumber, int timestamp, byte[] payload) {
        byte[] packet = new byte[12 + payload.length];
        ByteBuffer buf = ByteBuffer.wrap(packet);
        buf.put((byte) 0x80);
        buf.put((byte) (payloadType & 0xFF));
        buf.putShort((short) (sequenceNumber & 0xFFFF));
        buf.putInt(timestamp);
        buf.putInt(ssrc);
        buf.put(payload);
        return packet;
    }

    private static void shutdown(PeerConnection... peers) {
        for (PeerConnection p : peers) {
            if (p != null) p.close();
        }
    }

    private static Configuration tcpAnswerer(int port) {
        return Configuration.create().useTcpOnly("127.0.0.1:" + port, DtlsRole.CLIENT);
    }

    private static Configuration tcpOfferer() {
        return Configuration.create().setTransport("", "127.0.0.1:0", 0, NetworkType.TCP.value);
    }
}
