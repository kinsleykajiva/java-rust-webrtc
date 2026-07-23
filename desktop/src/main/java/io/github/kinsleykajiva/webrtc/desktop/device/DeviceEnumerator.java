package io.github.kinsleykajiva.webrtc.desktop.device;

import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import com.github.sarxos.webcam.Webcam;

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
        for (Webcam webcam : Webcam.getWebcams()) {
            devices.add(new VideoDevice(
                webcam.getName(),
                webcam.getName(),
                webcam.getViewSize().width,
                webcam.getViewSize().height,
                webcam.getFPS()
            ));
        }
        return devices;
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
