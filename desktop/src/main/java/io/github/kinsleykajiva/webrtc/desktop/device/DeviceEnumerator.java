package io.github.kinsleykajiva.webrtc.desktop.device;

import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import com.github.sarxos.webcam.Webcam;
import org.bytedeco.javacv.OpenCVFrameGrabber;

/**
 * Enumerates audio and video capture/playback devices available on the system.
 *
 * <p>Audio devices come from the Java Sound API (built into the JDK). Video devices
 * come from the webcam-capture library. This class provides a unified way to
 * discover and list all available devices.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * List<AudioDevice> microphones = DeviceEnumerator.audioInputs();
 * List<AudioDevice> speakers = DeviceEnumerator.audioOutputs();
 * List<VideoDevice> cameras = DeviceEnumerator.videoDevices();
 * }</pre>
 */
public final class DeviceEnumerator {

    private DeviceEnumerator() {}

    /**
     * Lists all available audio input devices (microphones).
     *
     * <p>Each returned {@link AudioDevice} represents a mixer that supports
     * {@link TargetDataLine} (audio capture).</p>
     *
     * @return list of available microphones, never null
     */
    public static List<AudioDevice> audioInputs() {
        List<AudioDevice> devices = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (mixer.isLineSupported(new javax.sound.sampled.DataLine.Info(
                    TargetDataLine.class,
                    new javax.sound.sampled.AudioFormat(
                        javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED,
                        48000, 16, 1, 2, 48000, false)))) {
                devices.add(new AudioDevice(
                    info.getName(),
                    info.getDescription(),
                    AudioDevice.DeviceType.INPUT,
                    48000,
                    1,
                    16
                ));
            }
        }
        return devices;
    }

    /**
     * Lists all available audio output devices (speakers/headphones).
     *
     * <p>Each returned {@link AudioDevice} represents a mixer that supports
     * {@link SourceDataLine} (audio playback).</p>
     *
     * @return list of available speakers, never null
     */
    public static List<AudioDevice> audioOutputs() {
        List<AudioDevice> devices = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (mixer.isLineSupported(new javax.sound.sampled.DataLine.Info(
                    SourceDataLine.class,
                    new javax.sound.sampled.AudioFormat(
                        javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED,
                        48000, 16, 1, 2, 48000, false)))) {
                devices.add(new AudioDevice(
                    info.getName(),
                    info.getDescription(),
                    AudioDevice.DeviceType.OUTPUT,
                    48000,
                    1,
                    16
                ));
            }
        }
        return devices;
    }

    /**
     * Lists all available video capture devices (webcams).
     *
     * <p>Each returned {@link VideoDevice} represents a webcam discovered by
     * the webcam-capture library.</p>
     *
     * @return list of available cameras, never null
     */
    public static List<VideoDevice> videoDevices() {
        List<VideoDevice> devices = new ArrayList<>();
        boolean webcamOk = false;
        try {
            for (Webcam webcam : Webcam.getWebcams()) {
                devices.add(new VideoDevice(
                    webcam.getName(),
                    webcam.getName(),
                    webcam.getViewSize().width,
                    webcam.getViewSize().height,
                    webcam.getFPS()
                ));
                webcamOk = true;
            }
        } catch (Throwable t) {
            // webcam-capture's native grabber (OpenIMAJGrabber) links against Apple's
            // QTKit framework, which was removed in macOS 10.15 (Catalina). On modern
            // macOS the native library cannot be loaded, so camera enumeration is
            // unavailable. Fall back to JavaCV (AVFoundation) below.
            warnWebcamUnavailable(t);
        }
        if (!webcamOk) {
            devices.addAll(javaCvVideoDevices());
        }
        return devices;
    }

    /**
     * Detects cameras through JavaCV's OpenCV grabber (AVFoundation backend on
     * macOS). Used as a fallback when webcam-capture cannot enumerate devices.
     *
     * @return list of AVFoundation cameras, possibly empty
     */
    private static List<VideoDevice> javaCvVideoDevices() {
        List<VideoDevice> devices = new ArrayList<>();
        OpenCVFrameGrabber grabber = null;
        try {
            grabber = new OpenCVFrameGrabber(0);
            grabber.start();
            devices.add(new VideoDevice(
                "avfoundation://0",
                "AVFoundation Camera (macOS)",
                640, 480, 30.0));
        } catch (Throwable t) {
            // No accessible camera via AVFoundation (none connected, or OpenCV native missing).
        } finally {
            if (grabber != null) {
                try { grabber.stop(); } catch (Throwable ignore) {}
                try { grabber.release(); } catch (Throwable ignore) {}
            }
        }
        return devices;
    }

    private static volatile boolean webcamWarned = false;

    private static void warnWebcamUnavailable(Throwable t) {
        if (webcamWarned) {
            return;
        }
        webcamWarned = true;
        System.err.println("[DeviceEnumerator] Webcam enumeration unavailable on this platform: "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
        System.err.println("[DeviceEnumerator] webcam-capture's native grabber requires the removed "
                + "QTKit framework on macOS 10.15+; camera capture will be unavailable here.");
    }

    /**
     * Returns the default audio input device (first available microphone).
     *
     * @return default microphone, or empty if none available
     */
    public static java.util.Optional<AudioDevice> defaultAudioInput() {
        List<AudioDevice> inputs = audioInputs();
        return inputs.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(inputs.get(0));
    }

    /**
     * Returns the default audio output device (first available speaker).
     *
     * @return default speaker, or empty if none available
     */
    public static java.util.Optional<AudioDevice> defaultAudioOutput() {
        List<AudioDevice> outputs = audioOutputs();
        return outputs.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(outputs.get(0));
    }

    /**
     * Returns the default video capture device (first available webcam).
     *
     * @return default webcam, or empty if none available
     */
    public static java.util.Optional<VideoDevice> defaultVideoDevice() {
        List<VideoDevice> devices = videoDevices();
        return devices.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(devices.get(0));
    }
}
