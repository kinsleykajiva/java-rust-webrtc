package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.NetworkType;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates dynamic addition and removal of video tracks on a peer connection,
 * re-negotiating after each change.
 *
 * <p>Flow:
 * <ol>
 *   <li>Create offerer + answerer peer connections over UDP.</li>
 *   <li>Initial SDP exchange (no tracks).</li>
 *   <li>Wait for ICE connection.</li>
 *   <li>Add a VP8 video track to the offerer, re-negotiate, stream frames.</li>
 *   <li>Remove that track, re-negotiate.</li>
 *   <li>Add a different VP8 video track, re-negotiate, stream frames.</li>
 *   <li>Close both peers.</li>
 * </ol>
 */
public class PlayFromDiskRenegotiationDemo {

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        final CountDownLatch connected = new CountDownLatch(2);
        final PeerConnection[] offererRef = new PeerConnection[1];
        final PeerConnection[] answererRef = new PeerConnection[1];

        // ---- Answerer (UDP, DTLS client) ----
        Configuration answererCfg = Configuration.create()
                .setTransport("127.0.0.1:0", "", 2 /* DtlsRole.CLIENT */, NetworkType.UDP.value);

        PeerConnection answerer = PeerConnection.create(answererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (offererRef[0] != null) {
                    offererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Answerer] connection state: " + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }

            @Override
            public void onTrack(int trackId, String label) {
                System.out.println("[Answerer] onTrack id=" + trackId + " label=" + label);
            }
        });
        answererRef[0] = answerer;

        // ---- Offerer (UDP, default DTLS role) ----
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
                System.out.println("[Offerer] connection state: " + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }
        });
        offererRef[0] = offerer;

        // ---- Step 1: Initial SDP exchange (no tracks) ----
        System.out.println("\n=== Step 1: Initial SDP exchange (no tracks) ===");
        SessionDescription offer = offerer.createOffer();
        offerer.setLocalDescription(offer);
        answerer.setRemoteDescription(offer);

        SessionDescription answer = answerer.createAnswer();
        answerer.setLocalDescription(answer);
        offerer.setRemoteDescription(answer);

        System.out.println("Waiting for ICE connection...");
        boolean ok = connected.await(20, TimeUnit.SECONDS);
        System.out.println("Connected: " + ok);
        if (!ok) {
            System.out.println("Failed to connect, aborting.");
            offerer.close();
            answerer.close();
            return;
        }

        // ---- Step 2: Add first VP8 video track, re-negotiate ----
        System.out.println("\n=== Step 2: Add first VP8 video track ===");
        TrackLocal trackA = TrackLocal.create(
                MediaKind.VIDEO,
                "stream-1",
                "video-track-1",
                "VP8 Video A",
                0,
                "video/VP8",
                90000,
                0,
                "");
        if (trackA == null) {
            System.out.println("Failed to create track A, aborting.");
            offerer.close();
            answerer.close();
            return;
        }
        int senderA = offerer.addTrack(trackA);
        System.out.println("Added track A, senderId=" + senderA);

        SessionDescription offerA = offerer.createOffer();
        offerer.setLocalDescription(offerA);
        answerer.setRemoteDescription(offerA);

        SessionDescription answerA = answerer.createAnswer();
        answerer.setLocalDescription(answerA);
        offerer.setRemoteDescription(answerA);

        Thread.sleep(1000);

        int payloadTypeA = offerer.senderGetPayloadType(senderA);
        System.out.println("Negotiated payload type for track A: " + payloadTypeA);

        // Stream synthetic frames on track A
        System.out.println("Streaming synthetic VP8 frames on track A...");
        byte[] frameA = createSyntheticVp8Frame((byte) 0xAA, 100);
        for (int i = 0; i < 30; i++) {
            trackA.writeSample(payloadTypeA, frameA, 33);
            Thread.sleep(33);
        }
        System.out.println("Finished streaming track A.");

        // ---- Step 3: Remove first video track, re-negotiate ----
        System.out.println("\n=== Step 3: Remove first video track ===");
        offerer.removeTrack(senderA);
        System.out.println("Removed track A (senderId=" + senderA + ")");

        SessionDescription offerRemove = offerer.createOffer();
        offerer.setLocalDescription(offerRemove);
        answerer.setRemoteDescription(offerRemove);

        SessionDescription answerRemove = answerer.createAnswer();
        answerer.setLocalDescription(answerRemove);
        offerer.setRemoteDescription(answerRemove);

        Thread.sleep(1000);
        System.out.println("Re-negotiation after remove complete.");
        System.out.println("Senders after removal: " + java.util.Arrays.toString(offerer.getSenders()));

        // ---- Step 4: Add second VP8 video track, re-negotiate ----
        System.out.println("\n=== Step 4: Add second VP8 video track ===");
        TrackLocal trackB = TrackLocal.create(
                MediaKind.VIDEO,
                "stream-2",
                "video-track-2",
                "VP8 Video B",
                0,
                "video/VP8",
                90000,
                0,
                "");
        if (trackB == null) {
            System.out.println("Failed to create track B, aborting.");
            offerer.close();
            answerer.close();
            return;
        }
        int senderB = offerer.addTrack(trackB);
        System.out.println("Added track B, senderId=" + senderB);

        SessionDescription offerB = offerer.createOffer();
        offerer.setLocalDescription(offerB);
        answerer.setRemoteDescription(offerB);

        SessionDescription answerB = answerer.createAnswer();
        answerer.setLocalDescription(answerB);
        offerer.setRemoteDescription(answerB);

        Thread.sleep(1000);

        int payloadTypeB = offerer.senderGetPayloadType(senderB);
        System.out.println("Negotiated payload type for track B: " + payloadTypeB);

        // Stream synthetic frames on track B
        System.out.println("Streaming synthetic VP8 frames on track B...");
        byte[] frameB = createSyntheticVp8Frame((byte) 0xBB, 100);
        for (int i = 0; i < 30; i++) {
            trackB.writeSample(payloadTypeB, frameB, 33);
            Thread.sleep(33);
        }
        System.out.println("Finished streaming track B.");

        // ---- Cleanup ----
        System.out.println("\n=== Closing ===");
        offerer.close();
        answerer.close();
        System.out.println("Done.");
    }

    /**
     * Creates a synthetic byte array that resembles a VP8 frame.
     *
     * <p>This is not a real VP8 bitstream; it is only used to exercise the
     * track write path. The first byte is the frame tag and the remaining bytes
     * are filled with a repeating pattern.</p>
     *
     * @param pattern the byte value used to fill the frame body
     * @param size    total frame size in bytes
     * @return a byte array of the given size
     */
    private static byte[] createSyntheticVp8Frame(byte pattern, int size) {
        byte[] frame = new byte[size];
        // Minimal VP8 keyframe header: frame tag 0x10 (key frame, width/height present)
        frame[0] = 0x10;
        for (int i = 1; i < size; i++) {
            frame[i] = pattern;
        }
        return frame;
    }
}
