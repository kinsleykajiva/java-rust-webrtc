package io.github.kinsleykajiva.desktop;

import io.github.kinsleykajiva.webrtc.desktop.BitrateConfig;
import io.github.kinsleykajiva.webrtc.desktop.audio.AudioCapture;
import io.github.kinsleykajiva.webrtc.desktop.device.AudioDevice;
import io.github.kinsleykajiva.webrtc.desktop.device.DeviceEnumerator;
import io.github.kinsleykajiva.webrtc.desktop.device.VideoDevice;
import io.github.kinsleykajiva.webrtc.desktop.video.VideoCapture;
import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.DataChannel;
import io.github.kinsleykajiva.webrtc.IceGatheringState;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.StatsReport;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interactive echo test driver for the desktop module.
 *
 * <p>Creates two local peer connections and pipes media between them. You can
 * toggle audio (microphone) and video (webcam) on or off, select specific
 * devices, choose a bitrate preset, and see real-time stats.</p>
 *
 * <h2>What it tests</h2>
 * <ul>
 *   <li>Device enumeration (cameras, microphones)</li>
 *   <li>Audio capture pipeline (TargetDataLine -> TrackLocal)</li>
 *   <li>Video capture pipeline (webcam -> TrackLocal)</li>
 *   <li>Peer connection creation and SDP exchange</li>
 *   <li>ICE gathering and connectivity</li>
 *   <li>Data channel messaging</li>
 *   <li>Track add/remove</li>
 *   <li>Stats reporting</li>
 * </ul>
 *
 * <h2>Run</h2>
 * <pre>
 * java --enable-native-access=ALL-UNNAMED \
 *      -cp "demo-desktop/target/classes;demo-desktop/target/dependency/*;desktop/target/classes;desktop/target/dependency/*;library/target/classes" \
 *      io.github.kinsleykajiva.desktop.EchoTestDriver
 * </pre>
 *
 * <h2>Interactive menu</h2>
 * <pre>
 * === WebRTC Echo Test ===
 *
 * Available devices:
 *   Cameras:
 *     [0] Logitech C920 (640x480, 30fps)
 *     [1] Virtual Camera (320x240, 15fps)
 *   Microphones:
 *     [0] Microphone (Realtek) (48000Hz, 1ch, 16bit)
 *     [1] Stereo Mix (48000Hz, 2ch, 16bit)
 *
 * Select options:
 *   [a] Toggle audio (currently: ON)
 *   [v] Toggle video (currently: ON)
 *   [c] Select camera
 *   [m] Select microphone
 *   [q] Select quality preset
 *   [s] Start test
 *   [x] Exit
 * </pre>
 */
public class EchoTestDriver {

    private static final Scanner scanner = new Scanner(System.in);

    // State
    private static boolean audioEnabled = true;
    private static boolean videoEnabled = true;
    private static int selectedCameraIndex = 0;
    private static int selectedMicIndex = 0;
    private static String selectedPreset = "Default";

    // Capture
    private static AudioCapture audioCapture;
    private static VideoCapture videoCapture;

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        System.out.println();
        System.out.println("===========================================");
        System.out.println("       WebRTC Echo Test Driver");
        System.out.println("===========================================");
        System.out.println();

        // Show available devices
        List<VideoDevice> cameras = DeviceEnumerator.videoDevices();
        List<AudioDevice> mics = DeviceEnumerator.audioInputs();

        System.out.println("Available devices:");
        System.out.println();

        System.out.println("  Cameras (" + cameras.size() + "):");
        if (cameras.isEmpty()) {
            System.out.println("    (none found)");
        } else {
            for (int i = 0; i < cameras.size(); i++) {
                System.out.println("    [" + i + "] " + cameras.get(i));
            }
        }
        System.out.println();

        System.out.println("  Microphones (" + mics.size() + "):");
        if (mics.isEmpty()) {
            System.out.println("    (none found)");
        } else {
            for (int i = 0; i < mics.size(); i++) {
                System.out.println("    [" + i + "] " + mics.get(i));
            }
        }
        System.out.println();

        if (cameras.isEmpty() && mics.isEmpty()) {
            System.out.println("No devices found. Connect a camera or microphone and try again.");
            return;
        }

        // Interactive menu
        boolean running = true;
        while (running) {
            printStatus(cameras, mics);
            System.out.print("> ");
            String input = scanner.nextLine().trim().toLowerCase();

            switch (input) {
                case "a" -> {
                    audioEnabled = !audioEnabled;
                    System.out.println("  Audio: " + (audioEnabled ? "ON" : "OFF"));
                }
                case "v" -> {
                    videoEnabled = !videoEnabled;
                    System.out.println("  Video: " + (videoEnabled ? "ON" : "OFF"));
                }
                case "c" -> {
                    if (cameras.isEmpty()) {
                        System.out.println("  No cameras available.");
                    } else {
                        System.out.print("  Select camera [0-" + (cameras.size() - 1) + "]: ");
                        try {
                            int idx = Integer.parseInt(scanner.nextLine().trim());
                            if (idx >= 0 && idx < cameras.size()) {
                                selectedCameraIndex = idx;
                                System.out.println("  Selected: " + cameras.get(idx).name());
                            } else {
                                System.out.println("  Invalid index.");
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("  Invalid input.");
                        }
                    }
                }
                case "m" -> {
                    if (mics.isEmpty()) {
                        System.out.println("  No microphones available.");
                    } else {
                        System.out.print("  Select microphone [0-" + (mics.size() - 1) + "]: ");
                        try {
                            int idx = Integer.parseInt(scanner.nextLine().trim());
                            if (idx >= 0 && idx < mics.size()) {
                                selectedMicIndex = idx;
                                System.out.println("  Selected: " + mics.get(idx).name());
                            } else {
                                System.out.println("  Invalid index.");
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("  Invalid input.");
                        }
                    }
                }
                case "q" -> {
                    System.out.println("  Quality presets:");
                    System.out.println("    [0] Low Bandwidth  (32kbps audio, 300kbps video, 320x240@15fps)");
                    System.out.println("    [1] Default        (64kbps audio, 1.5Mbps video, 640x480@30fps)");
                    System.out.println("    [2] High Quality   (128kbps audio, 4Mbps video, 1280x720@30fps)");
                    System.out.println("    [3] Screen Share   (64kbps audio, 2Mbps video, 1920x1080@15fps)");
                    System.out.print("  Select [0-3]: ");
                    try {
                        int idx = Integer.parseInt(scanner.nextLine().trim());
                        selectedPreset = switch (idx) {
                            case 0 -> "Low Bandwidth";
                            case 1 -> "Default";
                            case 2 -> "High Quality";
                            case 3 -> "Screen Share";
                            default -> selectedPreset;
                        };
                        System.out.println("  Preset: " + selectedPreset);
                    } catch (NumberFormatException e) {
                        System.out.println("  Invalid input.");
                    }
                }
                case "s" -> {
                    if (!audioEnabled && !videoEnabled) {
                        System.out.println("  Enable at least audio or video first.");
                    } else if (audioEnabled && mics.isEmpty()) {
                        System.out.println("  No microphone available. Disable audio or connect a mic.");
                    } else if (videoEnabled && cameras.isEmpty()) {
                        System.out.println("  No camera available. Disable video or connect a camera.");
                    } else {
                        runEchoTest(cameras, mics);
                    }
                }
                case "x", "quit", "exit" -> running = false;
                case "help", "?" -> printHelp();
                default -> System.out.println("  Unknown command. Type 'help' for options.");
            }
            System.out.println();
        }

        System.out.println("Done.");
    }

    private static void printStatus(List<VideoDevice> cameras, List<AudioDevice> mics) {
        System.out.println("---");
        System.out.printf("  Audio: %-3s  Video: %-3s  Preset: %s%n",
            audioEnabled ? "ON" : "OFF",
            videoEnabled ? "ON" : "OFF",
            selectedPreset);
        if (videoEnabled && !cameras.isEmpty()) {
            System.out.printf("  Camera: %s%n", cameras.get(selectedCameraIndex).name());
        }
        if (audioEnabled && !mics.isEmpty()) {
            System.out.printf("  Mic:    %s%n", mics.get(selectedMicIndex).name());
        }
        System.out.println("---");
        System.out.println("  Commands: [a]udio  [v]ideo  [c]amera  [m]ic  [q]uality  [s]tart  [x]exit");
    }

    private static void printHelp() {
        System.out.println("  Echo Test Driver - commands:");
        System.out.println("    a  - Toggle audio on/off");
        System.out.println("    v  - Toggle video on/off");
        System.out.println("    c  - Select camera");
        System.out.println("    m  - Select microphone");
        System.out.println("    q  - Select quality preset");
        System.out.println("    s  - Start echo test");
        System.out.println("    x  - Exit");
    }

    // ── Echo Test ──────────────────────────────────────────────────────────

    private static void runEchoTest(List<VideoDevice> cameras, List<AudioDevice> mics) throws Exception {
        System.out.println();
        System.out.println("===========================================");
        System.out.println("       Starting Echo Test");
        System.out.println("===========================================");
        System.out.println();

        BitrateConfig config = getBitrateConfig();
        System.out.println("Config: " + config);
        System.out.println();

        final CountDownLatch connected = new CountDownLatch(2);
        final AtomicBoolean testFailed = new AtomicBoolean(false);
        final AtomicInteger offererDcMessages = new AtomicInteger(0);
        final AtomicInteger answererDcMessages = new AtomicInteger(0);

        // ── Offerer ────────────────────────────────────────────────────────
        final PeerConnection[] offererRef = new PeerConnection[1];
        Configuration offererCfg = Configuration.create();
        PeerConnection offerer = PeerConnection.create(offererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {}

            @Override
            public void onIceGatheringStateChange(IceGatheringState state) {
                System.out.println("[Offerer]  Gathering: " + state);
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Offerer]  State: " + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                } else if (state == PeerConnectionState.FAILED) {
                    testFailed.set(true);
                }
            }

            @Override
            public void onDataChannel(int id, String label) {
                offererRef[0].setDataChannelCallbacks(id,
                    (chId, data) -> {
                        int count = offererDcMessages.incrementAndGet();
                        System.out.println("[Offerer]  DC recv #" + count + ": " + new String(data));
                    },
                    chId -> System.out.println("[Offerer]  DC open"),
                    chId -> System.out.println("[Offerer]  DC closed"));
            }
        });
        offererRef[0] = offerer;

        // ── Answerer ───────────────────────────────────────────────────────
        final PeerConnection[] answererRef = new PeerConnection[1];
        Configuration answererCfg = Configuration.create();
        PeerConnection answerer = PeerConnection.create(answererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {}

            @Override
            public void onIceGatheringStateChange(IceGatheringState state) {
                System.out.println("[Answerer] Gathering: " + state);
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Answerer] State: " + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                } else if (state == PeerConnectionState.FAILED) {
                    testFailed.set(true);
                }
            }

            @Override
            public void onDataChannel(int id, String label) {
                answererRef[0].setDataChannelCallbacks(id,
                    (chId, data) -> {
                        int count = answererDcMessages.incrementAndGet();
                        System.out.println("[Answerer] DC recv #" + count + ": " + new String(data));
                        // Echo back
                        answererRef[0].sendDataChannelText(chId, "echo: " + new String(data));
                    },
                    chId -> System.out.println("[Answerer] DC open"),
                    chId -> System.out.println("[Answerer] DC closed"));
            }
        });
        answererRef[0] = answerer;

        // ── Start captures ─────────────────────────────────────────────────
        int trackCount = 0;

        if (audioEnabled) {
            AudioDevice mic = mics.get(selectedMicIndex);
            System.out.println("Starting audio capture: " + mic.name());
            audioCapture = new AudioCapture(mic, config);
            audioCapture.setOnAudioLevel(level -> {
                if (level > 0.05) {
                    System.out.printf("[Audio]  Level: %.0f%%%n", level * 100);
                }
            });
            Optional<TrackLocal> audioTrack = audioCapture.start();
            if (audioTrack.isPresent()) {
                int senderId = offerer.addTrack(audioTrack.get());
                System.out.println("  Audio track added, sender: " + senderId);
                trackCount++;
            } else {
                System.out.println("  FAILED to start audio capture");
            }
        }

        if (videoEnabled) {
            VideoDevice cam = cameras.get(selectedCameraIndex);
            System.out.println("Starting video capture: " + cam.name());
            videoCapture = new VideoCapture(cam, config);
            int[] frameCount = {0};
            videoCapture.setOnFrame(frame -> {
                frameCount[0]++;
                if (frameCount[0] % 30 == 0) {
                    System.out.printf("[Video]  Frame #%d: %dx%d%n",
                        frameCount[0], frame.getWidth(), frame.getHeight());
                }
            });
            Optional<TrackLocal> videoTrack = videoCapture.start();
            if (videoTrack.isPresent()) {
                int senderId = offerer.addTrack(videoTrack.get());
                System.out.println("  Video track added, sender: " + senderId);
                trackCount++;
            } else {
                System.out.println("  FAILED to start video capture");
            }
        }

        if (trackCount == 0) {
            System.out.println("No tracks to send. Test aborted.");
            offerer.close();
            answerer.close();
            return;
        }

        // ── Data channel for echo test ─────────────────────────────────────
        int dcId = offerer.createDataChannel("echo-test", true);
        offerer.setDataChannelCallbacks(dcId,
            (id, data) -> {
                int count = offererDcMessages.incrementAndGet();
                System.out.println("[Offerer]  DC recv #" + count + ": " + new String(data));
            },
            id -> {
                System.out.println("[Offerer]  DC open — sending test messages");
                for (int i = 1; i <= 5; i++) {
                    offerer.sendDataChannelText(dcId, "ping " + i);
                }
            },
            id -> System.out.println("[Offerer]  DC closed"));

        // ── SDP exchange ───────────────────────────────────────────────────
        System.out.println();
        System.out.println("[Signal]  Creating offer...");
        SessionDescription offer = offerer.createOffer();
        offerer.setLocalDescription(offer);
        answerer.setRemoteDescription(offer);

        SessionDescription answer = answerer.createAnswer();
        answerer.setLocalDescription(answer);
        offerer.setRemoteDescription(answer);
        System.out.println("[Signal]  SDP exchange complete");
        System.out.println();

        // ── Wait for connection ────────────────────────────────────────────
        System.out.println("Waiting for connection (15s timeout)...");
        boolean ok = connected.await(15, TimeUnit.SECONDS);

        if (ok && !testFailed.get()) {
            System.out.println();
            System.out.println("Connected. Running echo test for 8 seconds...");
            Thread.sleep(8000);

            // ── Print stats ────────────────────────────────────────────────
            System.out.println();
            System.out.println("--- Stats (Offerer) ---");
            StatsReport offererReport = StatsReport.fetch(offerer);
            for (StatsReport.PeerConnectionStats pc : offererReport.peerConnections()) {
                System.out.printf("  DC opened: %d, closed: %d%n",
                    pc.dataChannelsOpened(), pc.dataChannelsClosed());
            }
            for (StatsReport.InboundRtpStats rtp : offererReport.inboundRtpStreams()) {
                System.out.printf("  RTP %s [ssrc=%d]: %d bytes, %d pkts, %d lost, jitter=%.4fs%n",
                    rtp.kind(), rtp.ssrc(), rtp.bytesReceived(),
                    rtp.packetsReceived(), rtp.packetsLost(), rtp.jitter());
            }

            System.out.println();
            System.out.println("--- Stats (Answerer) ---");
            StatsReport answererReport = StatsReport.fetch(answerer);
            for (StatsReport.InboundRtpStats rtp : answererReport.inboundRtpStreams()) {
                System.out.printf("  RTP %s [ssrc=%d]: %d bytes, %d pkts, %d lost, jitter=%.4fs%n",
                    rtp.kind(), rtp.ssrc(), rtp.bytesReceived(),
                    rtp.packetsReceived(), rtp.packetsLost(), rtp.jitter());
            }

            System.out.println();
            System.out.println("DC echo messages — offerer: " + offererDcMessages.get()
                + ", answerer: " + answererDcMessages.get());
        } else {
            System.out.println();
            System.out.println("Connection FAILED or timed out.");
        }

        // ── Cleanup ────────────────────────────────────────────────────────
        System.out.println();
        System.out.println("Stopping...");
        if (audioCapture != null) audioCapture.close();
        if (videoCapture != null) videoCapture.close();
        offerer.close();
        answerer.close();

        System.out.println("Echo test complete.");
    }

    private static BitrateConfig getBitrateConfig() {
        return switch (selectedPreset) {
            case "Low Bandwidth" -> BitrateConfig.lowBandwidth();
            case "High Quality" -> BitrateConfig.highQuality();
            case "Screen Share" -> BitrateConfig.screenShare();
            default -> BitrateConfig.defaults();
        };
    }
}
