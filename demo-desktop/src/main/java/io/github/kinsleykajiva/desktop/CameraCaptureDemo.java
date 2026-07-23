package io.github.kinsleykajiva.desktop;

import io.github.kinsleykajiva.webrtc.desktop.BitrateConfig;
import io.github.kinsleykajiva.webrtc.desktop.device.DeviceEnumerator;
import io.github.kinsleykajiva.webrtc.desktop.device.VideoDevice;
import io.github.kinsleykajiva.webrtc.desktop.video.VideoCapture;
import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.IceGatheringState;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates webcam capture piped into a WebRTC peer connection.
 *
 * <p>Captures video from the system webcam and sends it over a peer connection.
 * Two peer connections are created locally: an offerer (with camera) and an
 * answerer. The offerer sends video frames, the answerer receives them. This
 * verifies the full pipeline: webcam -> JPEG -> TrackLocal -> RTP -> remote.</p>
 *
 * <h2>Run</h2>
 * <pre>
 * java --enable-native-access=ALL-UNNAMED \
 *      -cp "..." io.github.kinsleykajiva.desktop.CameraCaptureDemo
 * </pre>
 */
public class CameraCaptureDemo {

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        System.out.println("=== Camera Capture Demo ===");
        System.out.println();

        // Find webcam
        Optional<VideoDevice> cam = DeviceEnumerator.defaultVideoDevice();
        if (cam.isEmpty()) {
            System.err.println("No webcam found. Please connect a camera and try again.");
            System.exit(1);
        }
        System.out.println("Using camera: " + cam.get());
        System.out.println();

        BitrateConfig config = BitrateConfig.defaults();
        System.out.println("Config: " + config);
        System.out.println();

        final CountDownLatch connected = new CountDownLatch(2);

        final PeerConnection[] answererRef = new PeerConnection[1];

        // ── Answerer ───────────────────────────────────────────────────────
        Configuration answererCfg = Configuration.create();
        PeerConnection answerer = PeerConnection.create(answererCfg, new PeerConnection.Observer() {
            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Answerer]  state: " + state);
                if (state == PeerConnectionState.CONNECTED) connected.countDown();
            }

            @Override
            public void onIceGatheringStateChange(IceGatheringState state) {
                System.out.println("[Answerer]  Gathering: " + state);
            }

            @Override
            public void onIceCandidate(String candidate, String sdpMid) {}

            @Override
            public void onDataChannel(int id, String label) {
                answererRef[0].setDataChannelCallbacks(id,
                    (chId, data) -> {},
                    chId -> {},
                    chId -> {});
            }
        });
        answererRef[0] = answerer;

        // ── Offerer ────────────────────────────────────────────────────────
        Configuration offererCfg = Configuration.create();
        PeerConnection offerer = PeerConnection.create(offererCfg, new PeerConnection.Observer() {
            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Offerer]   state: " + state);
                if (state == PeerConnectionState.CONNECTED) connected.countDown();
            }

            @Override
            public void onIceGatheringStateChange(IceGatheringState state) {
                System.out.println("[Offerer]   Gathering: " + state);
            }

            @Override
            public void onIceCandidate(String candidate, String sdpMid) {}
        });

        // Start camera capture
        VideoCapture capture = new VideoCapture(cam.get(), config);
        int[] frameCount = {0};
        capture.setOnFrame(frame -> {
            frameCount[0]++;
            if (frameCount[0] % 30 == 0) {
                System.out.printf("[Camera]    Frame #%d: %dx%d%n",
                    frameCount[0], frame.getWidth(), frame.getHeight());
            }
        });

        Optional<TrackLocal> videoTrack = capture.start();
        if (videoTrack.isEmpty()) {
            System.err.println("Failed to start camera capture.");
            offerer.close();
            answerer.close();
            System.exit(1);
        }

        int senderId = offerer.addTrack(videoTrack.get());
        System.out.println("Video track added, sender ID: " + senderId);

        // SDP exchange
        System.out.println();
        System.out.println("[Signal]    Creating offer...");
        SessionDescription offer = offerer.createOffer();
        offerer.setLocalDescription(offer);
        answerer.setRemoteDescription(offer);

        SessionDescription answer = answerer.createAnswer();
        answerer.setLocalDescription(answer);
        offerer.setRemoteDescription(answer);
        System.out.println("[Signal]    SDP exchange complete");
        System.out.println();

        // Wait for connection
        boolean ok = connected.await(15, TimeUnit.SECONDS);

        if (ok) {
            System.out.println("Streaming for 5 seconds...");
            Thread.sleep(5000);
        }

        System.out.println();
        System.out.println("Connected: " + ok);
        System.out.println("Frames sent: " + frameCount[0]);
        System.out.println("Stopping capture...");

        capture.stop();
        offerer.close();
        answerer.close();

        System.out.println("Done.");
    }
}
