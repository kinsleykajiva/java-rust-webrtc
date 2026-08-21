package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.DtlsRole;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.MimeTypes;
import io.github.kinsleykajiva.webrtc.NetworkType;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.IvfReader;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.TrackRemote;
import io.github.kinsleykajiva.webrtc.TransceiverDirection;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * save-to-disk-av1 demo.
 *
 * <p>A sender reads an AV1 IVF file and streams it to a receiver; the receiver writes
 * every received RTP payload into a fresh {@code .ivf} file on disk. AV1 is already
 * registered by the native engine's {@code register_default_codecs()}, so this needs no
 * native FFI change — the receiver simply records {@link TrackRemote#setRtpCallback}
 * payloads into an IVF container (exactly what the Rust example does via
 * {@code IVFWriter::write_rtp}).</p>
 */
public final class SaveToDiskAv1Demo {

    private static final int VIDEO_CLOCK_RATE = 90_000;
    private static final String OUTPUT = "output_av1_saved.ivf";

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        String input = MimeTypes.VIDEO_AV1 != null ? "demo-content/output_av1.ivf" : "demo-content/output_av1.ivf";
        if (args.length > 0) input = args[0];

        CountDownLatch connected = new CountDownLatch(2);
        final PeerConnection[] sender = {null};
        final PeerConnection[] receiver = {null};
        final IvfWriter[] writer = {null};
        final AtomicInteger framesSent = new AtomicInteger(0);
        final AtomicInteger framesSaved = new AtomicInteger(0);

        // Dimensions / timebase come from the source IVF header (used for the output file).
        int width = 320, height = 240, tbNum = 1, tbDen = 15;
        try (IvfReader r = new IvfReader(new java.io.FileInputStream(input))) {
            IvfReader.IvfFileHeader h = r.fileHeader();
            width = h.width(); height = h.height();
            tbNum = Math.max(1, h.timebaseNumerator()); tbDen = Math.max(1, h.timebaseDenominator());
        }

        // ---- RECEIVER (answerer): recvonly ----
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
                Av1Depacketizer depacketizer = new Av1Depacketizer();
                t.setRtpCallback((id, payload, pt, seq, ts, ssrc) -> {
                    try {
                        // Convert the AV1 RTP payload to a low-overhead bitstream frame.
                        byte[] obu = depacketizer.depacketize(payload);
                        if (obu.length > 0) {
                            writer[0].writeFrame(obu, ts);
                            framesSaved.incrementAndGet();
                        }
                    } catch (Exception e) {
                        System.err.println("[receiver] depacketize/write error: " + e.getMessage());
                    }
                });
            }
        });
        receiver[0].addTransceiver(MediaKind.VIDEO, TransceiverDirection.RECV_ONLY);

        // ---- SENDER (offerer): sendonly AV1 ----
        int ssrc = TrackLocal.randomSsrc();
        TrackLocal track = TrackLocal.create(
                MediaKind.VIDEO, "av1", "av1-video", "AV1", ssrc, "video/AV1", VIDEO_CLOCK_RATE, 0, "");
        if (track == null) {
            System.err.println("Failed to create AV1 track (AV1 may not be registered in the engine)");
            shutdown(sender[0], receiver[0]);
            return;
        }
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
        int senderId = sender[0].addTrack(track);

        negotiate(sender[0], receiver[0]);

        System.out.println("Waiting for connection...");
        if (!connected.await(20, TimeUnit.SECONDS)) {
            System.err.println("Connection timeout");
            shutdown(sender[0], receiver[0]);
            return;
        }

        writer[0] = new IvfWriter(new FileOutputStream(OUTPUT), width, height, tbNum, tbDen);
        System.out.println("Connected. Streaming AV1 from " + input + " -> " + OUTPUT);

        int payloadType = sender[0].senderGetPayloadType(senderId);
        if (payloadType < 0) payloadType = 96;
        System.out.println("AV1 negotiated payload type: " + payloadType);

        int frameDurationMs = (int) ((tbNum * 1000L) / tbDen); // e.g. 1/15 -> 66ms
        if (frameDurationMs <= 0) frameDurationMs = 33;
        try (IvfReader r = new IvfReader(new java.io.FileInputStream(input))) {
            // IvfReader.nextFrame() throws EOFException at end-of-file (rather than
            // returning null), so detect end-of-stream by catching it.
            while (true) {
                IvfReader.IvfFrame frame;
                try {
                    frame = r.nextFrame();
                } catch (java.io.EOFException eof) {
                    break;
                }
                if (frame == null) break;
                track.writeSample(payloadType, frame.data(), frameDurationMs);
                framesSent.incrementAndGet();
                Thread.sleep(frameDurationMs);
            }
        }
        Thread.sleep(1000);

        writer[0].close();
        System.out.println("Frames sent:    " + framesSent.get());
        System.out.println("Frames saved:   " + framesSaved.get());
        System.out.println(framesSaved.get() > 0
                ? "SUCCESS: AV1 RTP saved to " + OUTPUT
                : "FAILURE: no AV1 frames received");

        shutdown(sender[0], receiver[0]);
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
}
