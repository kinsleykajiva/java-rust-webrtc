package io.github.kinsleykajiva.webrtc.desktop.ui;

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
import io.github.kinsleykajiva.webrtc.SdpType;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import javafx.scene.control.Slider;
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

/**
 * A complete video call UI with device selection, local/remote video preview,
 * and WebRTC connection controls.
 *
 * <p>This window demonstrates the full desktop WebRTC pipeline: camera and
 * microphone capture, device selection, bitrate control, and peer connection
 * management with video/audio tracks.</p>
 *
 * <h2>Layout</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────┐
 * │  Device Selection                               │
 * │  [Camera ▼]  [Mic ▼]  [Bitrate ▼]             │
 * ├──────────────────────┬──────────────────────────┤
 * │                      │                          │
 * │   Local Video        │   Remote Video           │
 * │   (webcam preview)   │   (peer feed)            │
 * │                      │                          │
 * ├──────────────────────┴──────────────────────────┤
 * │  [Call]  [Hang Up]     Status: Disconnected     │
 * └─────────────────────────────────────────────────┘
 * </pre>
 */
public class VideoCallWindow extends Stage {

    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;

    // Device selectors
    private final ComboBox<String> cameraSelector = new ComboBox<>();
    private final ComboBox<String> micSelector = new ComboBox<>();
    private final ComboBox<String> bitrateSelector = new ComboBox<>();

    // Video canvases
    private final Canvas localCanvas = new Canvas(VIDEO_WIDTH, VIDEO_HEIGHT);
    private final Canvas remoteCanvas = new Canvas(VIDEO_WIDTH, VIDEO_HEIGHT);

    // Connection controls
    private final TextField signalingInput = new TextField();
    private final Button callButton = new Button("Call");
    private final Button hangUpButton = new Button("Hang Up");
    private final Label statusLabel = new Label("Disconnected");

    // Capture state
    private AudioCapture audioCapture;
    private VideoCapture videoCapture;
    private TrackLocal audioTrack;
    private TrackLocal videoTrack;

    // Peer connection
    private PeerConnection peerConnection;
    private int audioSenderId = -1;
    private int videoSenderId = -1;

    public VideoCallWindow() {
        setTitle("WebRTC Video Call");
        setMinWidth(VIDEO_WIDTH * 2 + 40);
        setMinHeight(VIDEO_HEIGHT + 180);

        WebRtc.initialize();
        setupUI();
        populateDevices();
    }

    private void setupUI() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #1e1e1e;");

        // ── Top: Device selection ──────────────────────────────────────────
        VBox topBar = new VBox(8);
        topBar.setPadding(new Insets(12));
        topBar.setStyle("-fx-background-color: #2d2d2d;");

        Label titleLabel = new Label("Device Selection");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
        titleLabel.setTextFill(Color.WHITE);

        HBox deviceRow = new HBox(12);
        deviceRow.setAlignment(Pos.CENTER_LEFT);

        cameraSelector.setPrefWidth(200);
        micSelector.setPrefWidth(200);
        bitrateSelector.setPrefWidth(150);

        deviceRow.getChildren().addAll(
            labeledBox("Camera", cameraSelector),
            labeledBox("Microphone", micSelector),
            labeledBox("Quality", bitrateSelector)
        );

        topBar.getChildren().addAll(titleLabel, deviceRow);
        root.setTop(topBar);

        // ── Center: Video canvases ─────────────────────────────────────────
        HBox videoArea = new HBox(12);
        videoArea.setPadding(new Insets(12));
        videoArea.setAlignment(Pos.CENTER);

        VBox localBox = videoBox("Local", localCanvas);
        VBox remoteBox = videoBox("Remote", remoteCanvas);

        HBox.setHgrow(localBox, Priority.ALWAYS);
        HBox.setHgrow(remoteBox, Priority.ALWAYS);

        videoArea.getChildren().addAll(localBox, remoteBox);
        root.setCenter(videoArea);

        // ── Bottom: Controls ───────────────────────────────────────────────
        VBox bottomBar = new VBox(8);
        bottomBar.setPadding(new Insets(12));
        bottomBar.setStyle("-fx-background-color: #2d2d2d;");

        signalingInput.setPromptText("Paste SDP offer/answer or ICE candidate here...");
        signalingInput.setPrefWidth(600);

        HBox controlRow = new HBox(12);
        controlRow.setAlignment(Pos.CENTER_LEFT);

        callButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14;");
        hangUpButton.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-size: 14;");
        hangUpButton.setDisable(true);

        statusLabel.setTextFill(Color.LIGHTGRAY);
        statusLabel.setFont(Font.font("System", 12));

        callButton.setOnAction(e -> startCall());
        hangUpButton.setOnAction(e -> hangUp());

        controlRow.getChildren().addAll(callButton, hangUpButton, statusLabel);
        bottomBar.getChildren().addAll(signalingInput, controlRow);
        root.setBottom(bottomBar);

        Scene scene = new Scene(root);
        setScene(scene);

        setOnCloseRequest(e -> {
            hangUp();
            Platform.exit();
            System.exit(0);
        });
    }

    private VBox labeledBox(String label, ComboBox<String> box) {
        Label l = new Label(label);
        l.setTextFill(Color.LIGHTGRAY);
        l.setFont(Font.font("System", 11));
        VBox vbox = new VBox(4, l, box);
        return vbox;
    }

    private VBox videoBox(String label, Canvas canvas) {
        Label l = new Label(label);
        l.setTextFill(Color.WHITE);
        l.setFont(Font.font("System", FontWeight.BOLD, 13));

        canvas.setStyle("-fx-background-color: #000000;");

        // Clear canvas with black
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.BLACK);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        gc.setFill(Color.GRAY);
        gc.setFont(Font.font("System", 16));
        gc.fillText("No video", canvas.getWidth() / 2 - 30, canvas.getHeight() / 2);

        VBox box = new VBox(4, l, canvas);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void populateDevices() {
        // Cameras
        List<VideoDevice> cameras = DeviceEnumerator.videoDevices();
        cameras.forEach(cam -> cameraSelector.getItems().add(cam.name()));
        if (!cameras.isEmpty()) cameraSelector.getSelectionModel().selectFirst();

        // Microphones
        List<AudioDevice> mics = DeviceEnumerator.audioInputs();
        mics.forEach(mic -> micSelector.getItems().add(mic.name()));
        if (!mics.isEmpty()) micSelector.getSelectionModel().selectFirst();

        // Bitrate presets
        bitrateSelector.getItems().addAll("Low Bandwidth", "Default", "High Quality", "Screen Share");
        bitrateSelector.getSelectionModel().select("Default");
    }

    private BitrateConfig getSelectedBitrate() {
        String selected = bitrateSelector.getValue();
        if (selected == null) return BitrateConfig.defaults();
        return switch (selected) {
            case "Low Bandwidth" -> BitrateConfig.lowBandwidth();
            case "High Quality" -> BitrateConfig.highQuality();
            case "Screen Share" -> BitrateConfig.screenShare();
            default -> BitrateConfig.defaults();
        };
    }

    private void startCall() {
        BitrateConfig bitrate = getSelectedBitrate();

        // Start audio capture
        String micName = micSelector.getValue();
        if (micName != null) {
            Optional<AudioDevice> mic = DeviceEnumerator.audioInputs().stream()
                .filter(d -> d.name().equals(micName))
                .findFirst();
            if (mic.isPresent()) {
                audioCapture = new AudioCapture(mic.get(), bitrate);
                audioCapture.setOnAudioLevel(level -> {
                    // Could update a level meter UI element here
                });
                Optional<TrackLocal> track = audioCapture.start();
                if (track.isPresent()) {
                    audioTrack = track.get();
                }
            }
        }

        // Start video capture
        String camName = cameraSelector.getValue();
        if (camName != null) {
            Optional<VideoDevice> cam = DeviceEnumerator.videoDevices().stream()
                .filter(d -> d.name().equals(camName))
                .findFirst();
            if (cam.isPresent()) {
                videoCapture = new VideoCapture(cam.get(), bitrate);
                videoCapture.setOnFrame(frame -> updateLocalPreview(frame));
                Optional<TrackLocal> track = videoCapture.start();
                if (track.isPresent()) {
                    videoTrack = track.get();
                }
            }
        }

        // Create peer connection
        Configuration cfg = Configuration.create();
        peerConnection = PeerConnection.create(cfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                Platform.runLater(() ->
                    statusLabel.setText("ICE: " + candidate.substring(0, Math.min(60, candidate.length())) + "..."));
            }

            @Override
            public void onIceGatheringStateChange(IceGatheringState state) {
                Platform.runLater(() -> statusLabel.setText("Gathering: " + state));
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                Platform.runLater(() -> {
                    statusLabel.setText("State: " + state);
                    if (state == PeerConnectionState.CONNECTED) {
                        callButton.setDisable(true);
                        hangUpButton.setDisable(false);
                    } else if (state == PeerConnectionState.FAILED || state == PeerConnectionState.CLOSED) {
                        callButton.setDisable(false);
                        hangUpButton.setDisable(true);
                    }
                });
            }

            @Override
            public void onDataChannel(int id, String label) {
                peerConnection.setDataChannelCallbacks(id,
                    (chId, data) -> System.out.println("DC: " + new String(data)),
                    chId -> System.out.println("DC open"),
                    chId -> System.out.println("DC closed"));
            }
        });

        // Add tracks
        if (audioTrack != null) {
            audioSenderId = peerConnection.addTrack(audioTrack);
        }
        if (videoTrack != null) {
            videoSenderId = peerConnection.addTrack(videoTrack);
        }

        Platform.runLater(() -> {
            callButton.setDisable(true);
            hangUpButton.setDisable(false);
            statusLabel.setText("Call started — create offer or paste answer");
        });
    }

    private void hangUp() {
        if (audioCapture != null) audioCapture.close();
        if (videoCapture != null) videoCapture.close();
        if (peerConnection != null) peerConnection.close();

        audioCapture = null;
        videoCapture = null;
        peerConnection = null;
        audioSenderId = -1;
        videoSenderId = -1;

        Platform.runLater(() -> {
            callButton.setDisable(false);
            hangUpButton.setDisable(true);
            statusLabel.setText("Disconnected");

            // Clear local preview
            GraphicsContext gc = localCanvas.getGraphicsContext2D();
            gc.setFill(Color.BLACK);
            gc.fillRect(0, 0, localCanvas.getWidth(), localCanvas.getHeight());

            // Clear remote preview
            gc = remoteCanvas.getGraphicsContext2D();
            gc.setFill(Color.BLACK);
            gc.fillRect(0, 0, remoteCanvas.getWidth(), remoteCanvas.getHeight());
        });
    }

    private void updateLocalPreview(BufferedImage frame) {
        Platform.runLater(() -> {
            WritableImage fxImage = SwingFXUtils.toFXImage(frame, null);
            GraphicsContext gc = localCanvas.getGraphicsContext2D();
            gc.drawImage(fxImage, 0, 0, localCanvas.getWidth(), localCanvas.getHeight());
        });
    }
}
