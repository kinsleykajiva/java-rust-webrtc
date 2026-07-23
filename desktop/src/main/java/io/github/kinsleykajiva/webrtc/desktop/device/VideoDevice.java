package io.github.kinsleykajiva.webrtc.desktop.device;

/**
 * Represents a video capture device (webcam) available on the system.
 *
 * <p>Video devices are enumerated through the webcam-capture library. Each device
 * wraps a {@code com.github.sarxos.webcam.Webcam} and provides resolution and
 * frame rate information.</p>
 *
 * @param id         unique identifier (device name or hash)
 * @param name       human-readable display name
 * @param width      default capture width in pixels
 * @param height     default capture height in pixels
 * @param maxFps     maximum frames per second
 */
public record VideoDevice(
    String id,
    String name,
    int width,
    int height,
    double maxFps
) {
    @Override
    public String toString() {
        return name + " (" + width + "x" + height + ", " + (int) maxFps + "fps)";
    }
}
