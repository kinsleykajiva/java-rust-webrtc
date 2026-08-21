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
 * Mirrors the Rust {@code rtcp-processing} example: a receiver installs the built-in
 * RTCP forwarder interceptor (via {@link Configuration#setRtcpForwarder}) and then drains
 * incoming RTCP packets with {@link PeerConnection#pollRtcp()}. The sender publishes a video
 * stream; the RTCP it generates (sender reports, etc.) is captured and counted by the receiver,
 * demonstrating that application code can observe and process RTCP.
 */
public final class RtcpProcessingDemo {

    private static final int VIDEO_CLOCK_RATE = 90_000;
    private static final int VP8_PAYLOAD_TYPE = 96;

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        CountDownLatch connected = new CountDownLatch(2);
        final PeerConnection[] sender = {null};
        final PeerConnection[] receiver = {null};
        final AtomicInteger rtpReceived = new AtomicInteger(0);

        // ---- RECEIVER (answerer): recvonly, RTCP forwarder enabled ----
        receiver[0] = PeerConnection.create(
                Configuration.create().useTcpOnly("127.0.0.1:8452", DtlsRole.CLIENT).setRtcpForwarder(true),
                new PeerConnection.Observer() {
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
                        TrackRemote t = TrackRemote.get(trackId);
                        t.setRtpCallback((id, payload, pt, seq, ts, ssrc) -> rtpReceived.incrementAndGet());
                    }
                });
        receiver[0].addTransceiver(MediaKind.VIDEO, TransceiverDirection.RECV_ONLY);

        // ---- SENDER (offerer): sendonly video ----
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
        int ssrc = TrackLocal.randomSsrc();
        TrackLocal track = TrackLocal.createRtpTrack(
                MediaKind.VIDEO, "rtcp", "video", "video", ssrc, "video/VP8", VIDEO_CLOCK_RATE);
        sender[0].addTrack(track);

        negotiate(sender[0], receiver[0]);

        System.out.println("Waiting for connection...");
        if (!connected.await(20, TimeUnit.SECONDS)) {
            System.err.println("Connection timeout");
            shutdown(sender[0], receiver[0]);
            return;
        }
        System.out.println("Connected. Streaming RTP so the sender generates RTCP...");

        for (int seq = 1; seq <= 100; seq++) {
            byte[] payload = new byte[]{0x10, 0x00, (byte) seq, 0x00};
            byte[] packet = buildRtpPacket(ssrc, VP8_PAYLOAD_TYPE, seq, seq * 3000, payload);
            try {
                track.writeRtp(packet);
            } catch (Exception e) {
                System.err.println("[sender] write error: " + e.getMessage());
            }
            Thread.sleep(5);
        }

        // Drain RTCP captured by the forwarder interceptor.
        int totalRtcp = 0;
        StringBuilder sample = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            String rtcp = receiver[0].pollRtcp();
            int n = countPackets(rtcp);
            if (n > 0 && sample.length() == 0) {
                sample.append(rtcp);
            }
            totalRtcp += n;
            Thread.sleep(200);
        }

        System.out.println("RTP received by receiver: " + rtpReceived.get());
        System.out.println("RTCP packets captured:    " + totalRtcp);
        if (sample.length() > 0) {
            System.out.println("Sample RTCP (hex blobs):  " + sample);
        }
        if (totalRtcp > 0) {
            System.out.println("SUCCESS: incoming RTCP observed and processed via pollRtcp().");
        } else {
            System.out.println("FAILURE: no RTCP was captured. (RTCP may need more media time.)");
        }

        shutdown(sender[0], receiver[0]);
    }

    private static int countPackets(String json) {
        if (json == null || json.equals("[]")) return 0;
        String inner = json.replace("[", "").replace("]", "").replace("\"", "");
        if (inner.isEmpty()) return 0;
        int count = 0;
        for (String tok : inner.split(",")) {
            if (!tok.isEmpty()) count++;
        }
        return count;
    }

    private static void negotiate(PeerConnection offerer, PeerConnection answerer) {
        SessionDescription offer = offerer.createOffer();
        offerer.setLocalDescription(offer);
        answerer.setRemoteDescription(offer);
        SessionDescription answer = answerer.createAnswer();
        answerer.setLocalDescription(answer);
        offerer.setRemoteDescription(answer);
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

    private static Configuration tcpOfferer() {
        return Configuration.create().setTransport("", "127.0.0.1:0", 0, NetworkType.TCP.value);
    }
}
