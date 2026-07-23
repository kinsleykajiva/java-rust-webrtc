package io.github.kinsleykajiva.desktop;

import io.github.kinsleykajiva.webrtc.desktop.BitrateConfig;
import io.github.kinsleykajiva.webrtc.desktop.audio.AudioCapture;
import io.github.kinsleykajiva.webrtc.desktop.device.AudioDevice;
import io.github.kinsleykajiva.webrtc.desktop.device.DeviceEnumerator;
import io.github.kinsleykajiva.webrtc.desktop.device.VideoDevice;
import io.github.kinsleykajiva.webrtc.desktop.video.VideoCapture;
import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.IceGatheringState;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.MimeTypes;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.StatsReport;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.TrackRemote;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * Full WebRTC echo test with JavaFX UI.
 *
 * <p>Creates two local peer connections (offerer and answerer) on the same machine
 * and runs the complete WebRTC signaling flow: SDP offer/answer exchange, ICE
 * candidate trickling, connectivity checks, and media/data transmission.</p>
 *
 * <h2>What it tests</h2>
 * <ul>
 *   <li>Full WebRTC offer/answer SDP exchange</li>
 *   <li>ICE candidate gathering and trickling</li>
 *   <li>Audio capture (microphone -> RTP -> remote)</li>
 *   <li>Video capture (webcam -> RTP -> remote)</li>
 *   <li>Remote video display (JPEG decode from RTP payload)</li>
 *   <li>Data channel bidirectional messaging</li>
 *   <li>Device selection (camera, microphone)</li>
 *   <li>Bitrate/quality presets</li>
 *   <li>Connection statistics</li>
 * </ul>
 *
 * <h2>Run</h2>
 * <pre>
 * C:\Users\Kinsley\.jdks\liberica-full-25.0.1\bin\java.exe ^
 *   --enable-native-access=ALL-UNNAMED ^
 *   --add-opens javafx.graphics/com.sun.javafx.application=ALL-UNNAMED ^
 *   -cp "..." io.github.kinsleykajiva.desktop.EchoTestDriver
 * </pre>
 */
public class EchoTestDriver extends Application {

    private static final int VIDEO_W = 640;
    private static final int VIDEO_H = 480;

    // ── UI ──────────────────────────────────────────────────────────────────
    private ComboBox<String> cameraBox;
    private ComboBox<String> micBox;
    private ComboBox<String> qualityBox;
    private Canvas localCanvas;
    private Canvas remoteCanvas;
    private Label statusLabel;
    private Label offererStateLabel;
    private Label answererStateLabel;
    private Label iceCountLabel;
    private ListView<String> logList;
    private Button startBtn;
    private Button stopBtn;
    private Button sendBtn;
    private TextField msgField;

    // ── Devices ─────────────────────────────────────────────────────────────
    private List<VideoDevice> cameras;
    private List<AudioDevice> mics;

    // ── Capture ─────────────────────────────────────────────────────────────
    private AudioCapture audioCapture;
    private VideoCapture videoCapture;

    // ── Peers ───────────────────────────────────────────────────────────────
    private PeerConnection offerer;
    private PeerConnection answerer;
    private final List<PeerConnection> allPeers = new CopyOnWriteArrayList<>();

    // ── State ───────────────────────────────────────────────────────────────
    private final AtomicInteger iceCandidateCount = new AtomicInteger(0);
    private volatile CountDownLatch connectedLatch = new CountDownLatch(2);
    private final AtomicBoolean running = new AtomicBoolean(false);

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        WebRtc.initialize();

        cameras = DeviceEnumerator.videoDevices();
        mics = DeviceEnumerator.audioInputs();

        primaryStage.setTitle("WebRTC Echo Test");
        primaryStage.setScene(new Scene(buildUI(), 1380, 700));
        primaryStage.setMinWidth(1380);
        primaryStage.setMinHeight(700);
        primaryStage.setOnCloseRequest(e -> {
            cleanup();
            Platform.exit();
            System.exit(0);
        });
        primaryStage.show();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  UI Layout
    // ═══════════════════════════════════════════════════════════════════════

    private BorderPane buildUI() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #1a1a2e;");

        // ── Top: Device selection ──────────────────────────────────────────
        root.setTop(buildTopBar());

        // ── Center: Video + Log ────────────────────────────────────────────
        root.setCenter(buildCenterArea());

        // ── Bottom: Controls ───────────────────────────────────────────────
        root.setBottom(buildBottomBar());

        return root;
    }

    private VBox buildTopBar() {
        VBox bar = new VBox(8);
        bar.setPadding(new Insets(10));
        bar.setStyle("-fx-background-color: #16213e;");

        Label title = new Label("WebRTC Echo Test");
        title.setFont(Font.font("Consolas", FontWeight.BOLD, 16));
        title.setTextFill(Color.CYAN);

        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER_LEFT);

        cameraBox = new ComboBox<>();
        cameraBox.setPrefWidth(220);
        for (VideoDevice cam : cameras) cameraBox.getItems().add(cam.name());
        if (!cameras.isEmpty()) cameraBox.getSelectionModel().selectFirst();

        micBox = new ComboBox<>();
        micBox.setPrefWidth(220);
        for (AudioDevice mic : mics) micBox.getItems().add(mic.name());
        if (!mics.isEmpty()) micBox.getSelectionModel().selectFirst();

        qualityBox = new ComboBox<>();
        qualityBox.setPrefWidth(150);
        qualityBox.getItems().addAll("Low Bandwidth", "Default", "High Quality", "Screen Share");
        qualityBox.getSelectionModel().select("Default");

        row.getChildren().addAll(
            vbox("Camera", cameraBox),
            vbox("Microphone", micBox),
            vbox("Quality", qualityBox)
        );

        bar.getChildren().addAll(title, row);
        return bar;
    }

    private HBox buildCenterArea() {
        HBox center = new HBox(12);
        center.setPadding(new Insets(10));

        // Local video
        localCanvas = new Canvas(VIDEO_W, VIDEO_H);
        clearCanvas(localCanvas, "Local (You)");

        VBox localBox = new VBox(4,
            label("Local Camera", Color.LIME),
            localCanvas
        );
        localBox.setAlignment(Pos.CENTER);

        // Remote video
        remoteCanvas = new Canvas(VIDEO_W, VIDEO_H);
        clearCanvas(remoteCanvas, "Remote (Peer)");

        VBox remoteBox = new VBox(4,
            label("Remote Video", Color.ORANGE),
            remoteCanvas
        );
        remoteBox.setAlignment(Pos.CENTER);

        // Log
        logList = new ListView<>();
        logList.setPrefWidth(300);
        logList.setStyle("-fx-control-inner-background: #0f0f23; -fx-text-fill: #00ff00;");
        VBox logBox = new VBox(4,
            label("Signaling Log", Color.CYAN),
            logList
        );

        HBox.setHgrow(localBox, Priority.ALWAYS);
        HBox.setHgrow(remoteBox, Priority.ALWAYS);

        center.getChildren().addAll(localBox, remoteBox, logBox);
        return center;
    }

    private VBox buildBottomBar() {
        VBox bar = new VBox(8);
        bar.setPadding(new Insets(10));
        bar.setStyle("-fx-background-color: #16213e;");

        // State indicators
        HBox stateRow = new HBox(20);
        stateRow.setAlignment(Pos.CENTER_LEFT);

        offererStateLabel = stateLabel("Offerer: idle", Color.GRAY);
        answererStateLabel = stateLabel("Answerer: idle", Color.GRAY);
        iceCountLabel = stateLabel("ICE candidates: 0", Color.WHITE);
        statusLabel = stateLabel("Ready", Color.YELLOW);
        statusLabel.setFont(Font.font("Consolas", FontWeight.BOLD, 13));

        stateRow.getChildren().addAll(offererStateLabel, answererStateLabel, iceCountLabel, statusLabel);

        // Controls
        HBox controlRow = new HBox(12);
        controlRow.setAlignment(Pos.CENTER_LEFT);

        startBtn = btn("Start Echo Test", Color.LIME);
        stopBtn = btn("Stop", Color.RED);
        stopBtn.setDisable(true);

        msgField = new TextField();
        msgField.setPrefWidth(300);
        msgField.setPromptText("Type a message to send over data channel...");

        sendBtn = btn("Send", Color.CORNFLOWERBLUE);
        sendBtn.setDisable(true);

        startBtn.setOnAction(e -> startTest());
        stopBtn.setOnAction(e -> stopTest());
        sendBtn.setOnAction(e -> sendMessage());

        controlRow.getChildren().addAll(startBtn, stopBtn, msgField, sendBtn);

        bar.getChildren().addAll(stateRow, controlRow);
        return bar;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  WebRTC Echo Test
    // ═══════════════════════════════════════════════════════════════════════

    private void startTest() {
        if (running.get()) return;
        running.set(true);

        Platform.runLater(() -> {
            startBtn.setDisable(true);
            stopBtn.setDisable(false);
            logList.getItems().clear();
            iceCandidateCount.set(0);
            connectedLatch = new CountDownLatch(2);
            offererStateLabel.setText("Offerer: creating...");
            answererStateLabel.setText("Answerer: creating...");
            iceCountLabel.setText("ICE candidates: 0");
            statusLabel.setText("Starting...");
            statusLabel.setTextFill(Color.YELLOW);
        });

        BitrateConfig config = getSelectedBitrate();
        log("Config: " + config);

        // ── Step 1: Create offerer peer ────────────────────────────────────
        Configuration offererCfg = Configuration.create();
        offerer = PeerConnection.create(offererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                int count = iceCandidateCount.incrementAndGet();
                Platform.runLater(() -> iceCountLabel.setText("ICE candidates: " + count));
                log("[Offerer]  ICE candidate #" + count + " (mid=" + sdpMid + ")");
                // Trickle to answerer
                if (answerer != null) {
                    try { answerer.addIceCandidate(candidate, sdpMid, 0); } catch (Exception ignored) {}
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                log("[Offerer]  State: " + state);
                Platform.runLater(() -> offererStateLabel.setText("Offerer: " + state));
                if (state == PeerConnectionState.CONNECTED) {
                    connectedLatch.countDown();
                    Platform.runLater(() -> {
                        statusLabel.setText("Connected!");
                        statusLabel.setTextFill(Color.LIME);
                    });
                } else if (state == PeerConnectionState.FAILED) {
                    Platform.runLater(() -> {
                        statusLabel.setText("FAILED");
                        statusLabel.setTextFill(Color.RED);
                    });
                }
            }

            @Override
            public void onIceGatheringStateChange(IceGatheringState state) {
                log("[Offerer]  Gathering: " + state);
            }

            @Override
            public void onDataChannel(int id, String label) {
                log("[Offerer]  Remote DC: " + label + " (id=" + id + ")");
            }

            @Override
            public void onTrack(int trackId, String label) {
                log("[Offerer]  Remote track: " + label + " (id=" + trackId + ")");
                handleRemoteTrack(trackId);
            }
        });
        allPeers.add(offerer);

        // ── Step 2: Create answerer peer ───────────────────────────────────
        Configuration answererCfg = Configuration.create();
        answerer = PeerConnection.create(answererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                int count = iceCandidateCount.incrementAndGet();
                Platform.runLater(() -> iceCountLabel.setText("ICE candidates: " + count));
                log("[Answerer] ICE candidate #" + count + " (mid=" + sdpMid + ")");
                // Trickle to offerer
                if (offerer != null) {
                    try { offerer.addIceCandidate(candidate, sdpMid, 0); } catch (Exception ignored) {}
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                log("[Answerer] State: " + state);
                Platform.runLater(() -> answererStateLabel.setText("Answerer: " + state));
                if (state == PeerConnectionState.CONNECTED) {
                    connectedLatch.countDown();
                } else if (state == PeerConnectionState.FAILED) {
                    Platform.runLater(() -> {
                        statusLabel.setText("FAILED");
                        statusLabel.setTextFill(Color.RED);
                    });
                }
            }

            @Override
            public void onIceGatheringStateChange(IceGatheringState state) {
                log("[Answerer] Gathering: " + state);
            }

            @Override
            public void onDataChannel(int id, String label) {
                log("[Answerer] Remote DC: " + label + " (id=" + id + ")");
                // Set up echo: receive message and send it back
                answerer.setDataChannelCallbacks(id,
                    (chId, data) -> {
                        String msg = new String(data);
                        log("[Answerer] DC recv: " + msg);
                        answerer.sendDataChannelText(chId, "echo: " + msg);
                        log("[Answerer] DC sent: echo: " + msg);
                    },
                    chId -> log("[Answerer] DC open"),
                    chId -> log("[Answerer] DC closed"));
            }

            @Override
            public void onTrack(int trackId, String label) {
                log("[Answerer] Remote track: " + label + " (id=" + trackId + ")");
                handleRemoteTrack(trackId);
            }
        });
        allPeers.add(answerer);

        // ── Step 3: Start media capture ────────────────────────────────────
        startCaptures(config);

        // ── Step 4: Create data channel on offerer ─────────────────────────
        int dcId = offerer.createDataChannel("echo-channel", true);
        offerer.setDataChannelCallbacks(dcId,
            (id, data) -> {
                String msg = new String(data);
                log("[Offerer]  DC recv: " + msg);
            },
            id -> {
                log("[Offerer]  DC open — ready to send");
                Platform.runLater(() -> sendBtn.setDisable(false));
            },
            id -> log("[Offerer]  DC closed"));

        // ── Step 5: SDP Offer ──────────────────────────────────────────────
        log("[Signal]   Creating SDP offer...");
        SessionDescription offer = offerer.createOffer();
        offerer.setLocalDescription(offer);
        log("[Signal]   Offer set locally");

        // ── Step 6: Answerer processes offer ───────────────────────────────
        log("[Signal]   Answerer setting remote description (offer)...");
        answerer.setRemoteDescription(offer);
        log("[Signal]   Answerer creating SDP answer...");
        SessionDescription answer = answerer.createAnswer();
        answerer.setLocalDescription(answer);
        log("[Signal]   Answer set locally");

        // ── Step 7: Offerer processes answer ───────────────────────────────
        log("[Signal]   Offerer setting remote description (answer)...");
        offerer.setRemoteDescription(answer);
        log("[Signal]   SDP exchange complete — ICE connectivity checks starting");
        Platform.runLater(() -> statusLabel.setText("ICE checking..."));

        // ── Step 8: Wait for connection (background thread) ────────────────
        new Thread(() -> {
            try {
                boolean ok = connectedLatch.await(20, TimeUnit.SECONDS);
                if (ok) {
                    log("[System]   Connected! Running echo test for 10 seconds...");
                    Thread.sleep(10_000);
                    Platform.runLater(() -> {
                        statusLabel.setText("Test complete — see log");
                        statusLabel.setTextFill(Color.CYAN);
                    });
                    printStats();
                } else {
                    log("[System]   Connection timed out after 20s");
                    Platform.runLater(() -> {
                        statusLabel.setText("Timed out");
                        statusLabel.setTextFill(Color.RED);
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "EchoTest-Waiter").start();
    }

    private void startCaptures(BitrateConfig config) {
        // Start audio
        String micName = micBox.getValue();
        if (micName != null) {
            mics.stream().filter(d -> d.name().equals(micName)).findFirst().ifPresent(mic -> {
                log("[Capture]  Mic: " + mic.name());
                audioCapture = new AudioCapture(mic, config);
                audioCapture.setOnAudioLevel(level -> {
                    // Could drive a level meter here
                });
                Optional<TrackLocal> track = audioCapture.start();
                if (track.isPresent()) {
                    int sid = offerer.addTrack(track.get());
                    log("[Capture]  Audio track added (sender=" + sid + ")");
                } else {
                    log("[Capture]  FAILED to start mic");
                }
            });
        }

        // Start video
        String camName = cameraBox.getValue();
        if (camName != null) {
            cameras.stream().filter(d -> d.name().equals(camName)).findFirst().ifPresent(cam -> {
                log("[Capture]  Cam: " + cam.name());
                videoCapture = new VideoCapture(cam, config);
                int[] frames = {0};
                videoCapture.setOnFrame(frame -> {
                    frames[0]++;
                    // Update local preview
                    Platform.runLater(() -> {
                        WritableImage fx = SwingFXUtils.toFXImage(frame, null);
                        localCanvas.getGraphicsContext2D().drawImage(fx, 0, 0, VIDEO_W, VIDEO_H);
                    });
                });
                Optional<TrackLocal> track = videoCapture.start();
                if (track.isPresent()) {
                    int sid = offerer.addTrack(track.get());
                    log("[Capture]  Video track added (sender=" + sid + ")");
                } else {
                    log("[Capture]  FAILED to start camera");
                }
            });
        }
    }

    private void handleRemoteTrack(int trackId) {
        TrackRemote remote = TrackRemote.get(trackId);
        if (remote == null) {
            log("[Track]    Unknown track id=" + trackId);
            return;
        }

        int kind = remote.getKind();
        log("[Track]    id=" + trackId + " kind=" + (kind == 1 ? "audio" : "video"));

        remote.setOpenCallback((tid, ssrc, rid) -> {
            String codec = remote.getCodec(ssrc);
            log("[Track]    Open: track=" + tid + " ssrc=" + ssrc + " rid=" + rid + " codec=" + codec);
        });

        remote.setRtpCallback((tid, payload, payloadType, seq, timestamp, ssrc) -> {
            // If this is video (JPEG payload), decode and display
            if (kind == 2 && payload != null && payload.length > 0) {
                try {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(payload));
                    if (img != null) {
                        Platform.runLater(() -> {
                            WritableImage fx = SwingFXUtils.toFXImage(img, null);
                            remoteCanvas.getGraphicsContext2D()
                                .drawImage(fx, 0, 0, VIDEO_W, VIDEO_H);
                        });
                    }
                } catch (Exception e) {
                    // Not a valid JPEG frame, skip
                }
            }
        });

        // Register so the polling task picks up callbacks
        TrackRemote.register(trackId);
    }

    private void sendMessage() {
        if (offerer == null) return;
        String msg = msgField.getText().trim();
        if (msg.isEmpty()) return;
        offerer.sendDataChannelText(0, msg);
        log("[Offerer]  DC sent: " + msg);
        msgField.clear();
    }

    private void stopTest() {
        cleanup();
        Platform.runLater(() -> {
            startBtn.setDisable(false);
            stopBtn.setDisable(true);
            sendBtn.setDisable(true);
            statusLabel.setText("Stopped");
            statusLabel.setTextFill(Color.GRAY);
            offererStateLabel.setText("Offerer: idle");
            answererStateLabel.setText("Answerer: idle");
            clearCanvas(localCanvas, "Local (You)");
            clearCanvas(remoteCanvas, "Remote (Peer)");
        });
    }

    private void cleanup() {
        running.set(false);
        if (audioCapture != null) { audioCapture.close(); audioCapture = null; }
        if (videoCapture != null) { videoCapture.close(); videoCapture = null; }
        for (PeerConnection pc : allPeers) {
            try { pc.close(); } catch (Exception ignored) {}
        }
        allPeers.clear();
        offerer = null;
        answerer = null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Stats
    // ═══════════════════════════════════════════════════════════════════════

    private void printStats() {
        if (offerer == null) return;

        log("--- Offerer Stats ---");
        try {
            StatsReport report = StatsReport.fetch(offerer);
            for (StatsReport.PeerConnectionStats pc : report.peerConnections()) {
                log("  DC opened=" + pc.dataChannelsOpened() + " closed=" + pc.dataChannelsClosed());
            }
            for (StatsReport.InboundRtpStats rtp : report.inboundRtpStreams()) {
                log("  RTP " + rtp.kind() + " [ssrc=" + rtp.ssrc() + "]: "
                    + rtp.bytesReceived() + " bytes, "
                    + rtp.packetsReceived() + " pkts, "
                    + rtp.packetsLost() + " lost, "
                    + String.format("jitter=%.4f", rtp.jitter()));
            }
        } catch (Exception e) {
            log("  Stats error: " + e.getMessage());
        }

        log("--- Answerer Stats ---");
        if (answerer != null) {
            try {
                StatsReport report = StatsReport.fetch(answerer);
                for (StatsReport.InboundRtpStats rtp : report.inboundRtpStreams()) {
                    log("  RTP " + rtp.kind() + " [ssrc=" + rtp.ssrc() + "]: "
                        + rtp.bytesReceived() + " bytes, "
                        + rtp.packetsReceived() + " pkts, "
                        + rtp.packetsLost() + " lost");
                }
            } catch (Exception e) {
                log("  Stats error: " + e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private BitrateConfig getSelectedBitrate() {
        return switch (qualityBox.getValue()) {
            case "Low Bandwidth" -> BitrateConfig.lowBandwidth();
            case "High Quality" -> BitrateConfig.highQuality();
            case "Screen Share" -> BitrateConfig.screenShare();
            default -> BitrateConfig.defaults();
        };
    }

    private void log(String msg) {
        System.out.println(msg);
        Platform.runLater(() -> logList.getItems().add(msg));
    }

    private void clearCanvas(Canvas c, String text) {
        GraphicsContext gc = c.getGraphicsContext2D();
        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, c.getWidth(), c.getHeight());
        gc.setFill(Color.DARKGRAY);
        gc.setFont(Font.font("Consolas", 16));
        gc.fillText(text, c.getWidth() / 2 - 60, c.getHeight() / 2);
    }

    private static Label label(String text, Color color) {
        Label l = new Label(text);
        l.setTextFill(color);
        l.setFont(Font.font("Consolas", FontWeight.BOLD, 12));
        return l;
    }

    private static Label stateLabel(String text, Color color) {
        Label l = new Label(text);
        l.setTextFill(color);
        l.setFont(Font.font("Consolas", 12));
        return l;
    }

    private static Button btn(String text, Color color) {
        Button b = new Button(text);
        b.setStyle(String.format("-fx-background-color: %s; -fx-text-fill: black; -fx-font-weight: bold;",
            toHex(color)));
        return b;
    }

    private static VBox vbox(String labelText, ComboBox<String> box) {
        Label l = new Label(labelText);
        l.setTextFill(Color.LIGHTGRAY);
        l.setFont(Font.font("Consolas", 11));
        return new VBox(4, l, box);
    }

    private static String toHex(Color c) {
        return String.format("#%02X%02X%02X",
            (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }
}
