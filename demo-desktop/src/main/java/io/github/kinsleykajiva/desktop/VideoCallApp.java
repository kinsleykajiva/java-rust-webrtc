package io.github.kinsleykajiva.desktop;

import io.github.kinsleykajiva.webrtc.desktop.ui.VideoCallWindow;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * Launches the full video call UI with device selection, local/remote video
 * preview, and WebRTC connection controls.
 *
 * <p>This is the most comprehensive desktop demo. It combines microphone capture,
 * camera capture, device selection, bitrate control, and a JavaFX interface
 * into a single video call application.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Camera and microphone device selection</li>
 *   <li>Bitrate preset selection (Low Bandwidth, Default, High Quality, Screen Share)</li>
 *   <li>Local video preview from webcam</li>
 *   <li>Remote video display area</li>
 *   <li>Call/Hang Up controls</li>
 *   <li>SDP signaling input</li>
 *   <li>Connection status display</li>
 * </ul>
 *
 * <h2>Run</h2>
 * <pre>
 * java --enable-native-access=ALL-UNNAMED \
 *      --module-path /path/to/javafx/lib \
 *      --add-modules javafx.controls,javafx.media,javafx.graphics \
 *      -cp "..." io.github.kinsleykajiva.desktop.VideoCallApp
 * </pre>
 */
public class VideoCallApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        VideoCallWindow window = new VideoCallWindow();
        window.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
