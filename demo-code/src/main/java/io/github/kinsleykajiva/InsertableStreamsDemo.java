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
 * Insertable streams demo (mirrors the Rust {@code insertable-streams} example).
 *
 * <p>The Rust example XOR-encrypts every encoded frame before it is sent and relies on
 * the receiver applying the inverse transform. This Java port does the same at the RTP
 * payload level: the sender inserts a transform (XOR with a fixed key) onto every
 * outgoing RTP payload, and the receiver inserts the inverse transform onto every
 * incoming payload, recovering the original bytes. This is the "insertable streams"
 * pattern — a custom transform applied to media in transit.</p>
 *
 * <p>Note on the native API: the {@code webrtc} fork used here does <em>not</em> expose
 * {@code set_rtp_transform}/{@code set_rtcp_transform}; its example applies the transform
 * at the frame level before {@code write_sample}. This demo reproduces that behaviour
 * (transform on the payload) and therefore needs no native FFI change. A fully transparent,
 * engine-driven transform would require adding an interceptor/transform API to the FFI.</p>
 */
public final class InsertableStreamsDemo {

    private static final int VIDEO_CLOCK_RATE = 90_000;
    private static final int VP8_PAYLOAD_TYPE = 96;
    private static final byte CIPHER_KEY = (byte) 0xAA;

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        CountDownLatch connected = new CountDownLatch(2);

        final PeerConnection[] sender = {null};
        final PeerConnection[] receiver = {null};

        final AtomicInteger transformedSent = new AtomicInteger(0);
        final AtomicInteger recovered = new AtomicInteger(0);
        final AtomicInteger recoveryMismatch = new AtomicInteger(0);

        // ---- RECEIVER (answerer): recvonly, applies inverse transform ----
        receiver[0] = PeerConnection.create(tcpAnswerer(8443), new PeerConnection.Observer() {
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
                t.setRtpCallback((id, payload, pt, seq, ts, ssrc) -> {
                    // Inverse transform: XOR the payload back to recover the original.
                    byte[] original = xor(payload, CIPHER_KEY);
                    // The sender stamps a recognizable marker byte (0x10) as payload[0].
                    if (original.length > 0 && original[0] == 0x10) {
                        recovered.incrementAndGet();
                    } else {
                        recoveryMismatch.incrementAndGet();
                    }
                });
            }
        });
        receiver[0].addTransceiver(MediaKind.VIDEO, TransceiverDirection.RECV_ONLY);

        // ---- SENDER (offerer): sendonly, applies outbound transform ----
        int ssrc = TrackLocal.randomSsrc();
        TrackLocal track = TrackLocal.createRtpTrack(
                MediaKind.VIDEO, "insert", "insert-vp8", "Insertable VP8", ssrc, "video/VP8", VIDEO_CLOCK_RATE);
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
        sender[0].addTrack(track);

        negotiate(sender[0], receiver[0]);

        System.out.println("Waiting for connection...");
        if (!connected.await(20, TimeUnit.SECONDS)) {
            System.err.println("Connection timeout");
            shutdown(sender[0], receiver[0]);
            return;
        }
        System.out.println("Connected. Streaming transformed RTP (XOR key=0x"
                + String.format("%02X", CIPHER_KEY & 0xFF) + ")...");

        int total = 200;
        for (int seq = 0; seq < total; seq++) {
            // Build a synthetic VP8 payload: marker byte + a few payload bytes carrying seq.
            byte[] frame = new byte[]{
                    0x10,
                    (byte) (seq & 0xFF),
                    (byte) ((seq >> 8) & 0xFF),
                    (byte) (seq & 0xFF),
                    (byte) ((seq >> 8) & 0xFF)
            };
            // Insertable-stream transform on send: XOR the whole payload.
            byte[] transformed = xor(frame, CIPHER_KEY);
            byte[] packet = buildRtpPacket(ssrc, VP8_PAYLOAD_TYPE, seq, seq * 20, transformed);
            track.writeRtp(packet);
            transformedSent.incrementAndGet();
            Thread.sleep(10);
        }
        Thread.sleep(1000);

        System.out.println("Transformed sent:  " + transformedSent.get());
        System.out.println("Recovered (ok):    " + recovered.get());
        System.out.println("Recovery mismatch: " + recoveryMismatch.get());
        if (recovered.get() > 0 && recoveryMismatch.get() == 0) {
            System.out.println("SUCCESS: insertable-streams transform applied on send and inverted on receive.");
        } else {
            System.out.println("FAILURE: transform did not round-trip.");
        }

        shutdown(sender[0], receiver[0]);
    }

    private static byte[] xor(byte[] data, byte key) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key);
        }
        return out;
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
}
