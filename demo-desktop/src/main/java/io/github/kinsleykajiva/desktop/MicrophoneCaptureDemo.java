package io.github.kinsleykajiva.desktop;

import io.github.kinsleykajiva.webrtc.desktop.BitrateConfig;
import io.github.kinsleykajiva.webrtc.desktop.audio.AudioCapture;
import io.github.kinsleykajiva.webrtc.desktop.device.AudioDevice;
import io.github.kinsleykajiva.webrtc.desktop.device.DeviceEnumerator;
import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.DataChannel;
import io.github.kinsleykajiva.webrtc.IceGatheringState;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates microphone capture piped into a WebRTC peer connection.
 *
 * <p>Captures audio from the system microphone and sends it over a peer connection.
 * Two peer connections are created locally: an offerer (with mic) and an answerer.
 * The offerer sends audio, the answerer receives it. This verifies the full
 * pipeline: microphone capture -> PCM -> TrackLocal -> RTP -> remote peer.</p>
 *
 * <h2>Run</h2>
 * <pre>
 * java --enable-native-access=ALL-UNNAMED \
 *      -cp "..." io.github.kinsleykajiva.desktop.MicrophoneCaptureDemo
 * </pre>
 */
public class MicrophoneCaptureDemo {

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        System.out.println("=== Microphone Capture Demo ===");
        System.out.println();

        // Find microphone
        Optional<AudioDevice> mic = DeviceEnumerator.defaultAudioInput();
        if (mic.isEmpty()) {
            System.err.println("No microphone found. Please connect a microphone and try again.");
            System.exit(1);
        }
        System.out.println("Using microphone: " + mic.get());
        System.out.println();

        BitrateConfig config = BitrateConfig.defaults();
        System.out.println("Config: " + config);
        System.out.println();

        final CountDownLatch connected = new CountDownLatch(2);

        final PeerConnection[] answererRef = new PeerConnection[1];

        // ── Answerer (receives audio) ──────────────────────────────────────
        Configuration answererCfg = Configuration.create();
        PeerConnection answerer = PeerConnection.create(answererCfg, new PeerConnection.Observer() {
            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Answerer]  state: " + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }

            @Override
            public void onDataChannel(int id, String label) {
                answererRef[0].setDataChannelCallbacks(id,
                    (chId, data) -> System.out.println("[Answerer]  DC: " + new String(data)),
                    chId -> System.out.println("[Answerer]  DC open"),
                    chId -> System.out.println("[Answerer]  DC closed"));
            }

            @Override
            public void onIceGatheringStateChange(IceGatheringState state) {
                System.out.println("[Answerer]  Gathering: " + state);
            }

            @Override
            public void onIceCandidate(String candidate, String sdpMid) {}
        });
        answererRef[0] = answerer;

        // ── Offerer (captures and sends audio) ─────────────────────────────
        Configuration offererCfg = Configuration.create();
        PeerConnection offerer = PeerConnection.create(offererCfg, new PeerConnection.Observer() {
            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Offerer]   state: " + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }

            @Override
            public void onIceGatheringStateChange(IceGatheringState state) {
                System.out.println("[Offerer]   Gathering: " + state);
            }

            @Override
            public void onIceCandidate(String candidate, String sdpMid) {}
        });

        // Start microphone capture
        AudioCapture capture = new AudioCapture(mic.get(), config);
        capture.setOnAudioLevel(level -> {
            if (level > 0.01) {
                System.out.printf("[Mic]       Level: %.2f%%%n", level * 100);
            }
        });

        Optional<TrackLocal> audioTrack = capture.start();
        if (audioTrack.isEmpty()) {
            System.err.println("Failed to start microphone capture.");
            offerer.close();
            answerer.close();
            System.exit(1);
        }

        int senderId = offerer.addTrack(audioTrack.get());
        System.out.println("Audio track added, sender ID: " + senderId);

        // Data channel for signaling
        int dcId = offerer.createDataChannel("signaling", true);
        offerer.setDataChannelCallbacks(dcId,
            (id, data) -> System.out.println("[Offerer]   DC: " + new String(data)),
            id -> {
                System.out.println("[Offerer]   DC open — sending test message");
                offerer.sendDataChannelText(dcId, "Hello from mic demo!");
            },
            id -> System.out.println("[Offerer]   DC closed"));

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
            Thread.sleep(3000);
        }

        System.out.println();
        System.out.println("Connected: " + ok);
        System.out.println("Stopping capture...");

        capture.stop();
        offerer.close();
        answerer.close();

        System.out.println("Done.");
    }
}
