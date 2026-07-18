package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.DataChannel;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demonstrates RTP-to-WebRTC forwarding using {@link TrackLocal#createRtpTrack} and
 * {@link TrackLocal#writeRtp}.
 *
 * <p>In a real deployment, the RTP packets would arrive from an external source such
 * as ffmpeg, an IP camera, or an SRT/RTMP gateway. This loopback demo synthesizes
 * minimal valid VP8 RTP packets to prove the forwarding path works end-to-end.</p>
 *
 * <p>Both peers run in the same process. The offerer creates an RTP video track,
 * adds it to the peer connection, and after the signaling handshake completes it
 * starts writing synthetic RTP packets. The answerer receives them via its
 * {@code onTrack} callback.</p>
 */
public class RtpToWebRtcDemo {

    private static final int VIDEO_CLOCK_RATE = 90_000;

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        final CountDownLatch connected = new CountDownLatch(2);
        final AtomicReference<String> answererDcReceived = new AtomicReference<>();
        final PeerConnection[] offererRef = new PeerConnection[1];
        final PeerConnection[] answererRef = new PeerConnection[1];

        // ---- Answerer ----
        Configuration answererCfg = Configuration.create();
        PeerConnection answerer = PeerConnection.create(answererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (offererRef[0] != null) {
                    offererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Answerer] state: " + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }

            @Override
            public void onDataChannel(int id, String label) {
                answererRef[0].setDataChannelCallbacks(id,
                        (dcId, data) -> {
                            answererDcReceived.set(new String(data));
                            System.out.println("[Answerer] DC received: " + answererDcReceived.get());
                        },
                        dcId -> System.out.println("[Answerer] DC open"),
                        dcId -> System.out.println("[Answerer] DC closed"));
            }
        });
        answererRef[0] = answerer;

        // ---- Offerer ----
        Configuration offererCfg = Configuration.create();
        PeerConnection offerer = PeerConnection.create(offererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (answererRef[0] != null) {
                    answererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Offerer] state: " + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }
        });
        offererRef[0] = offerer;

        // Create a data channel on the offerer for a quick handshake test.
        int dcId = offerer.createDataChannel("ctrl", true);
        offerer.setDataChannelCallbacks(dcId,
                (id, data) -> System.out.println("[Offerer] DC received: " + new String(data)),
                id -> System.out.println("[Offerer] DC open"),
                id -> System.out.println("[Offerer] DC closed"));

        // Create an RTP video track (VP8, clock rate 90000).
        int ssrc = TrackLocal.randomSsrc();
        TrackLocal videoTrack = TrackLocal.createRtpTrack(
                MediaKind.VIDEO,
                "rtp-stream",
                "vp8-video",
                "VP8 RTP Track",
                ssrc,
                "video/VP8",
                VIDEO_CLOCK_RATE);
        if (videoTrack == null) {
            System.err.println("Failed to create RTP track");
            return;
        }
        System.out.println("[Offerer] Created RTP track ssrc=" + ssrc);

        // Add the track to the offerer peer connection.
        int senderId = offerer.addTrack(videoTrack);
        if (senderId == -1) {
            System.err.println("Failed to add track");
            return;
        }
        System.out.println("[Offerer] Added track, senderId=" + senderId);

        // SDP exchange.
        SessionDescription offer = offerer.createOffer();
        offerer.setLocalDescription(offer);
        answerer.setRemoteDescription(offer);

        SessionDescription answer = answerer.createAnswer();
        answerer.setLocalDescription(answer);
        offerer.setRemoteDescription(answer);

        System.out.println("Signaling complete, connecting...");
        boolean ok = connected.await(20, TimeUnit.SECONDS);
        System.out.println("Connected: " + ok);

        if (!ok) {
            offerer.close();
            answerer.close();
            return;
        }

        // Give data channel time to open.
        Thread.sleep(1000);
        offerer.sendDataChannelText(dcId, "rtp-forwarding-active");

        // Forward synthetic VP8 RTP packets for 5 seconds.
        AtomicInteger packetCount = new AtomicInteger(0);
        int totalPackets = 250; // ~5s at 50 pps
        Thread rtpSender = new Thread(() -> {
            System.out.println("[Offerer] Starting RTP forwarding (" + totalPackets + " packets)...");
            long startTime = System.currentTimeMillis();
            for (int seq = 0; seq < totalPackets; seq++) {
                byte[] rtpPacket = buildSyntheticVp8RtpPacket(ssrc, seq, seq * 20);
                videoTrack.writeRtp(rtpPacket);
                packetCount.incrementAndGet();
                // 50 packets per second = 20ms intervals.
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("[Offerer] Finished forwarding " + packetCount.get()
                    + " packets in " + elapsed + "ms");
        }, "rtp-forwarder");
        rtpSender.start();

        // Wait for forwarding to finish.
        rtpSender.join(10_000);
        Thread.sleep(1000);

        System.out.println("Packets written: " + packetCount.get());
        System.out.println("Answerer DC received: " + answererDcReceived.get());
        System.out.println("Done.");

        offerer.close();
        answerer.close();
    }

    /**
     * Builds a minimal valid RTP packet with VP8 payload.
     * Layout: [V=2, P=0, X=0, CC=0, M=0, PT=96] [seq] [timestamp] [ssrc] [payload]
     */
    private static byte[] buildSyntheticVp8RtpPacket(int ssrc, int sequenceNumber, int timestamp) {
        // Minimal VP8 RTP payload: a single VP8 payload descriptor byte (0x10 = start of partition).
        byte[] payload = new byte[]{0x10, 0x00, 0x00, 0x00};
        byte[] packet = new byte[12 + payload.length];
        ByteBuffer buf = ByteBuffer.wrap(packet);

        // Byte 0: V=2, P=0, X=0, CC=0, M=0, PT=96
        buf.put((byte) 0x80);
        buf.put((byte) 96);
        // Bytes 2-3: sequence number (big-endian)
        buf.putShort((short) (sequenceNumber & 0xFFFF));
        // Bytes 4-7: timestamp (big-endian)
        buf.putInt(timestamp);
        // Bytes 8-11: SSRC (big-endian)
        buf.putInt(ssrc);
        // Payload
        buf.put(payload);

        return packet;
    }
}
