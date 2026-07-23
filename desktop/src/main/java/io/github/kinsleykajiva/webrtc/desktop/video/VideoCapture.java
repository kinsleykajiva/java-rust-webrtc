package io.github.kinsleykajiva.webrtc.desktop.video;

import io.github.kinsleykajiva.webrtc.desktop.BitrateConfig;
import io.github.kinsleykajiva.webrtc.desktop.device.VideoDevice;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.MimeTypes;
import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamResolution;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/**
 * Captures video from a webcam and feeds it into a WebRTC {@link TrackLocal}.
 *
 * <p>Uses the webcam-capture library to read frames from the camera. Frames are
 * converted to JPEG and sent as sample payloads through the track. A local
 * preview callback allows the UI to display the camera feed.</p>
 *
 * <h2>Architecture</h2>
 * <pre>
 *   Webcam
 *       |
 *       v
 *   Webcam.getImage() (webcam-capture library)
 *       |
 *       v
 *   VideoCaptureThread (background thread, reads BufferedImage frames)
 *       |
 *       ├──> TrackLocal.writeSample(payloadType, jpegBytes, durationMs)
 *       |        |
 *       |        v
 *       |    WebRTC peer connection (RTP)
 *       |
 *       └──> onFrame callback (JavaFX Canvas for local preview)
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * BitrateConfig config = BitrateConfig.defaults();
 * VideoDevice cam = DeviceEnumerator.defaultVideoDevice().orElseThrow();
 *
 * VideoCapture capture = new VideoCapture(cam, config);
 * capture.setOnFrame(frame -> {
 *     // Update JavaFX Canvas with the frame
 *     javafx.scene.image.Image img = SwingFXUtils.toFXImage(frame, null);
 *     canvas.getGraphicsContext2D().drawImage(img, 0, 0);
 * });
 *
 * TrackLocal videoTrack = capture.start();
 * int senderId = peerConnection.addTrack(videoTrack);
 *
 * // Later...
 * capture.stop();
 * }</pre>
 */
public class VideoCapture implements AutoCloseable {

    private final VideoDevice device;
    private final BitrateConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Webcam webcam;
    private Thread captureThread;
    private Consumer<BufferedImage> onFrame;

    /**
     * Creates a video capture for the specified device and configuration.
     *
     * @param device the webcam to capture from
     * @param config bitrate, resolution, and frame rate configuration
     */
    public VideoCapture(VideoDevice device, BitrateConfig config) {
        this.device = device;
        this.config = config;
    }

    /**
     * Sets a callback that receives each captured frame as a {@link BufferedImage}.
     * This is called on the capture thread, so UI updates should be dispatched
     * to the JavaFX application thread.
     *
     * @param callback receives each frame, or null to disable preview
     */
    public void setOnFrame(Consumer<BufferedImage> callback) {
        this.onFrame = callback;
    }

    /**
     * Starts capturing video and returns a {@link TrackLocal} ready to be
     * added to a peer connection.
     *
     * <p>The returned track sends JPEG-compressed frames at the configured
     * resolution and frame rate. The MIME type is {@code video/VP8} for
     * compatibility with WebRTC receivers.</p>
     *
     * @return a local video track, or empty if the device cannot be opened
     */
    public Optional<TrackLocal> start() {
        if (running.get()) {
            return Optional.empty();
        }

        try {
            // Find the webcam matching our device
            webcam = null;
            for (Webcam w : Webcam.getWebcams()) {
                if (w.getName().equals(device.name())) {
                    webcam = w;
                    break;
                }
            }

            if (webcam == null) {
                System.err.println("Webcam not found: " + device.name());
                return Optional.empty();
            }

            // Set custom resolution if different from default
            Dimension targetSize = new Dimension(config.getVideoWidth(), config.getVideoHeight());
            webcam.setViewSize(targetSize);

            if (!webcam.isOpen()) {
                webcam.open();
            }

            TrackLocal track = TrackLocal.create(
                MediaKind.VIDEO,
                "video-stream",
                "camera-track",
                device.name(),
                TrackLocal.randomSsrc(),
                MimeTypes.VIDEO_VP8,
                90000,  // VP8 clock rate
                0,
                ""
            );

            running.set(true);
            captureThread = new Thread(() -> captureLoop(track), "VideoCapture-" + device.name());
            captureThread.setDaemon(true);
            captureThread.start();

            return Optional.of(track);

        } catch (Exception e) {
            System.err.println("Failed to open webcam: " + e.getMessage());
            return Optional.empty();
        }
    }

    private void captureLoop(TrackLocal track) {
        long frameIntervalMs = 1000 / config.getVideoFps();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        while (running.get()) {
            long frameStart = System.currentTimeMillis();

            BufferedImage frame = webcam.getImage();
            if (frame != null) {
                // Convert to JPEG bytes
                baos.reset();
                try {
                    ImageIO.write(frame, "jpg", baos);
                    byte[] jpegBytes = baos.toByteArray();

                    // Send to WebRTC track
                    track.writeSample(0, jpegBytes, (int) frameIntervalMs);
                } catch (Exception e) {
                    System.err.println("Failed to encode frame: " + e.getMessage());
                }

                // Notify UI of new frame
                if (onFrame != null) {
                    onFrame.accept(frame);
                }
            }

            // Sleep to maintain target frame rate
            long elapsed = System.currentTimeMillis() - frameStart;
            long sleepMs = frameIntervalMs - elapsed;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Stops video capture and releases the webcam.
     */
    public void stop() {
        running.set(false);
        if (captureThread != null) {
            captureThread.interrupt();
            try {
                captureThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (webcam != null && webcam.isOpen()) {
            webcam.close();
        }
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running.get();
    }

    public VideoDevice getDevice() {
        return device;
    }

    public BitrateConfig getConfig() {
        return config;
    }
}
