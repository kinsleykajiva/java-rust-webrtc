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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demonstrates RTP forwarding between two peer connections, mirroring the Rust
 * {@code rtp-forwarder} example.
 *
 * <p>Topology (all in one process, ICE-over-TCP on loopback):</p>
 * <pre>
 *   source  ──(pair 1)──▶  bridgeIn   (recvonly)
 *                                    │  onRtpPacket → rebuild RTP → writeRtp
 *                                    ▼
 *   sink    ◀──(pair 2)──  bridgeOut  (sendonly, TrackLocalStaticRTP)
 * </pre>
 *
 * <p>{@code bridgeIn} receives VP8 RTP from {@code source}, reconstructs a full
 * RTP packet from the payload delivered by {@link TrackRemote#setRtpCallback}, and
 * forwards it onto {@code bridgeOut}'s {@link TrackLocal} (which rewrites the SSRC
 * and sends it to {@code sink}). {@code sink} counts the packets it receives,
 * proving the media traversed two independent peer connections.</p>
 *
 * <p>Note: the Rust example also forwards RTCP (PLI/NACK) back to the source. The
 * current Java binding exposes RTCP <em>send</em> ({@link TrackRemote#writeRtcpPli})
 * but not RTCP <em>receive</em>, so this demo forwards RTP only.</p>
 */
public final class RtpForwarderDemo {

    private static final int VIDEO_CLOCK_RATE = 90_000;
    private static final int VP8_PAYLOAD_TYPE = 96;

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        CountDownLatch allConnected = new CountDownLatch(4);

        final PeerConnection[] source = {null};
        final PeerConnection[] bridgeIn = {null};
        final PeerConnection[] bridgeOut = {null};
        final PeerConnection[] sink = {null};
        final TrackLocal[] outTrack = {null};

        final AtomicInteger sinkPackets = new AtomicInteger(0);
        final AtomicInteger forwardedPackets = new AtomicInteger(0);

        // The onRtpPacket callback runs on a native tokio worker thread, where calling
        // TrackLocal.writeRtp (which uses block_on internally) would panic ("Cannot start
        // a runtime from within a runtime"). Hand the reconstructed packets to a queue
        // drained by a dedicated thread that performs the actual writeRtp calls.
        final java.util.concurrent.BlockingQueue<byte[]> forwardQueue =
                new java.util.concurrent.LinkedBlockingQueue<>();

        // ---- SINK (answerer of pair 2): recvonly ----
        sink[0] = PeerConnection.create(tcpAnswerer(8444), new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (bridgeOut[0] != null) bridgeOut[0].addIceCandidate(candidate, sdpMid, 0);
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[sink] " + state);
                if (state == PeerConnectionState.CONNECTED) allConnected.countDown();
            }

            @Override
            public void onTrack(int trackId, String label) {
                TrackRemote t = TrackRemote.get(trackId);
                t.setRtpCallback((id, payload, pt, seq, ts, ssrc) -> sinkPackets.incrementAndGet());
            }
        });

        // ---- BRIDGE OUT (offerer of pair 2): sendonly ----
        int outSsrc = TrackLocal.randomSsrc();
        outTrack[0] = TrackLocal.createRtpTrack(
                MediaKind.VIDEO, "fwd", "fwd-vp8", "Forwarded VP8", outSsrc, "video/VP8", VIDEO_CLOCK_RATE);
        bridgeOut[0] = PeerConnection.create(tcpOfferer(), new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (sink[0] != null) sink[0].addIceCandidate(candidate, sdpMid, 0);
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[bridgeOut] " + state);
                if (state == PeerConnectionState.CONNECTED) allConnected.countDown();
            }
        });
        bridgeOut[0].addTrack(outTrack[0]);

        // ---- BRIDGE IN (answerer of pair 1): recvonly ----
        bridgeIn[0] = PeerConnection.create(tcpAnswerer(8443), new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (source[0] != null) source[0].addIceCandidate(candidate, sdpMid, 0);
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[bridgeIn] " + state);
                if (state == PeerConnectionState.CONNECTED) allConnected.countDown();
            }

            @Override
            public void onTrack(int trackId, String label) {
                TrackRemote t = TrackRemote.get(trackId);
                t.setOpenCallback((id, ssrc, rid) -> System.out.println("[bridgeIn] track open ssrc=" + ssrc));
                t.setRtpCallback((id, payload, pt, seq, ts, ssrc) -> {
                    forwardQueue.offer(buildRtpPacket(outSsrc, pt, seq, ts, payload));
                    forwardedPackets.incrementAndGet();
                });
            }
        });
        bridgeIn[0].addTransceiver(MediaKind.VIDEO, TransceiverDirection.RECV_ONLY);

        // ---- SOURCE (offerer of pair 1): sendonly ----
        int srcSsrc = TrackLocal.randomSsrc();
        TrackLocal srcTrack = TrackLocal.createRtpTrack(
                MediaKind.VIDEO, "src", "src-vp8", "Source VP8", srcSsrc, "video/VP8", VIDEO_CLOCK_RATE);
        source[0] = PeerConnection.create(tcpOfferer(), new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (bridgeIn[0] != null) bridgeIn[0].addIceCandidate(candidate, sdpMid, 0);
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[source] " + state);
                if (state == PeerConnectionState.CONNECTED) allConnected.countDown();
            }
        });
        source[0].addTrack(srcTrack);

        // ---- Signaling (offer/answer per pair, ICE trickled by observers) ----
        negotiate(source[0], bridgeIn[0]);
        negotiate(bridgeOut[0], sink[0]);

        System.out.println("Waiting for all connections...");
        if (!allConnected.await(20, TimeUnit.SECONDS)) {
            System.err.println("Connection timeout");
            shutdown(source[0], bridgeIn[0], bridgeOut[0], sink[0]);
            return;
        }
        System.out.println("All connected. Starting RTP source...");

        Thread forwarder = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    byte[] packet = forwardQueue.take();
                    outTrack[0].writeRtp(packet);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "rtp-forwarder-drain");
        forwarder.start();

        int total = 200;
        for (int seq = 0; seq < total; seq++) {
            byte[] packet = buildRtpPacket(srcSsrc, VP8_PAYLOAD_TYPE, seq, seq * 20, new byte[]{0x10, 0x00, 0x00, 0x00});
            srcTrack.writeRtp(packet);
            Thread.sleep(10);
        }
        Thread.sleep(1000);
        forwarder.interrupt();
        forwarder.join(2000);

        System.out.println("Source sent:    " + total);
        System.out.println("Forwarded:      " + forwardedPackets.get());
        System.out.println("Sink received:  " + sinkPackets.get());
        if (forwardedPackets.get() > 0 && sinkPackets.get() > 0) {
            System.out.println("SUCCESS: RTP forwarded across two peer connections.");
        } else {
            System.out.println("FAILURE: media was not forwarded end-to-end.");
        }

        shutdown(source[0], bridgeIn[0], bridgeOut[0], sink[0]);
    }

    private static void negotiate(PeerConnection offerer, PeerConnection answerer) {
        SessionDescription offer = offerer.createOffer();
        offerer.setLocalDescription(offer);
        answerer.setRemoteDescription(offer);
        SessionDescription answer = answerer.createAnswer();
        answerer.setLocalDescription(answer);
        offerer.setRemoteDescription(answer);
    }

    private static Configuration tcpAnswerer(int port) {
        return Configuration.create().useTcpOnly("127.0.0.1:" + port, DtlsRole.CLIENT);
    }

    private static Configuration tcpOfferer() {
        return Configuration.create().setTransport("", "127.0.0.1:0", 0, NetworkType.TCP.value);
    }

    private static void shutdown(PeerConnection... peers) {
        for (PeerConnection p : peers) {
            if (p != null) p.close();
        }
    }

    /**
     * Builds a full RTP packet (12-byte header + payload). {@link TrackLocal#writeRtp}
     * parses this, rewrites the SSRC to the track's configured SSRC, and forwards it.
     */
    private static byte[] buildRtpPacket(int ssrc, int payloadType, int sequenceNumber, int timestamp, byte[] payload) {
        byte[] packet = new byte[12 + payload.length];
        ByteBuffer buf = ByteBuffer.wrap(packet);
        buf.put((byte) 0x80);                                  // V=2, P=0, X=0, CC=0
        buf.put((byte) (payloadType & 0xFF));
        buf.putShort((short) (sequenceNumber & 0xFFFF));
        buf.putInt(timestamp);
        buf.putInt(ssrc);
        buf.put(payload);
        return packet;
    }
}
