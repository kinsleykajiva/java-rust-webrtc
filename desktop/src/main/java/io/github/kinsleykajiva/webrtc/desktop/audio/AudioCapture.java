package io.github.kinsleykajiva.webrtc.desktop.audio;

import io.github.kinsleykajiva.webrtc.desktop.BitrateConfig;
import io.github.kinsleykajiva.webrtc.desktop.device.AudioDevice;
import io.github.kinsleykajiva.webrtc.TrackLocal;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.MimeTypes;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * Captures audio from a microphone and feeds it into a WebRTC {@link TrackLocal}.
 *
 * <p>Uses the Java Sound API {@link TargetDataLine} to read PCM audio data from
 * the system microphone. Audio frames are read in a background thread and written
 * to the track at regular intervals based on the configured sample rate and
 * frame duration.</p>
 *
 * <h2>Architecture</h2>
 * <pre>
 *   Microphone
 *       |
 *       v
 *   TargetDataLine (javax.sound.sampled)
 *       |
 *       v
 *   AudioCaptureThread (background thread, reads PCM bytes)
 *       |
 *       v
 *   TrackLocal.writeSample(payloadType, pcmData, durationMs)
 *       |
 *       v
 *   WebRTC peer connection (RTP)
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * BitrateConfig config = BitrateConfig.defaults();
 * AudioDevice mic = DeviceEnumerator.defaultAudioInput().orElseThrow();
 *
 * AudioCapture capture = new AudioCapture(mic, config);
 * capture.setOnAudioLevel(level -> System.out.println("Level: " + level));
 *
 * TrackLocal audioTrack = capture.start();
 * int senderId = peerConnection.addTrack(audioTrack);
 *
 * // Later...
 * capture.stop();
 * }</pre>
 */
public class AudioCapture implements AutoCloseable {

    private final AudioDevice device;
    private final BitrateConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private TargetDataLine line;
    private Thread captureThread;
    private Consumer<Double> onAudioLevel;

    /**
     * Creates an audio capture for the specified device and configuration.
     *
     * @param device the microphone to capture from
     * @param config bitrate and format configuration
     */
    public AudioCapture(AudioDevice device, BitrateConfig config) {
        this.device = device;
        this.config = config;
    }

    /**
     * Sets a callback that receives the current audio level (0.0 to 1.0)
     * on each frame read. Useful for UI level meters.
     *
     * @param callback receives audio level, or null to disable
     */
    public void setOnAudioLevel(Consumer<Double> callback) {
        this.onAudioLevel = callback;
    }

    /**
     * Starts capturing audio and returns a {@link TrackLocal} ready to be
     * added to a peer connection.
     *
     * <p>The returned track uses PCM encoding at the configured sample rate.
     * For WebRTC, the track uses {@code audio/pcm} MIME type with the
     * configured sample rate and channel count.</p>
     *
     * @return a local audio track, or empty if the device cannot be opened
     */
    public Optional<TrackLocal> start() {
        if (running.get()) {
            return Optional.empty();
        }

        try {
            AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                config.getAudioSampleRate(),
                config.getAudioBitsPerSample(),
                config.getAudioChannels(),
                config.audioFrameSize(),
                config.getAudioSampleRate(),
                false  // little-endian
            );

            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
            Mixer.Info targetMixer = null;

            // Find the mixer that matches our device
            for (Mixer.Info mi : mixerInfos) {
                if (mi.getName().equals(device.id())) {
                    targetMixer = mi;
                    break;
                }
            }

            if (targetMixer != null) {
                Mixer mixer = AudioSystem.getMixer(targetMixer);
                if (mixer.isLineSupported(info)) {
                    line = (TargetDataLine) mixer.getLine(info);
                }
            }

            // Fall back to default
            if (line == null) {
                line = (TargetDataLine) AudioSystem.getLine(info);
            }

            line.open(format);
            line.start();

            TrackLocal track = TrackLocal.create(
                MediaKind.AUDIO,
                "audio-stream",
                "mic-track",
                device.name(),
                TrackLocal.randomSsrc(),
                MimeTypes.AUDIO_PCMU,
                config.getAudioSampleRate(),
                config.getAudioChannels(),
                ""
            );

            running.set(true);
            captureThread = new Thread(() -> captureLoop(track), "AudioCapture-" + device.name());
            captureThread.setDaemon(true);
            captureThread.start();

            return Optional.of(track);

        } catch (Exception e) {
            System.err.println("Failed to open audio device: " + e.getMessage());
            return Optional.empty();
        }
    }

    private void captureLoop(TrackLocal track) {
        // Calculate bytes per frame based on 20ms audio chunks (standard for Opus/PCM in RTP)
        int bytesPerFrame = (int) (config.getAudioSampleRate() * config.audioFrameSize() * 20 / 1000);
        byte[] buffer = new byte[bytesPerFrame];

        while (running.get()) {
            int bytesRead = line.read(buffer, 0, buffer.length);
            if (bytesRead > 0) {
                // Calculate duration in ms for this chunk
                long durationMs = (long) (bytesRead * 1000.0 / (config.getAudioSampleRate() * config.audioFrameSize()));

                // Write to WebRTC track
                track.writeSample(0, buffer, (int) durationMs);

                // Compute audio level for UI callback
                if (onAudioLevel != null) {
                    double level = computeLevel(buffer, bytesRead);
                    onAudioLevel.accept(level);
                }
            }
        }
    }

    private double computeLevel(byte[] data, int length) {
        long sum = 0;
        int samples = length / 2; // 16-bit samples
        for (int i = 0; i < length - 1; i += 2) {
            short sample = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
            sum += Math.abs(sample);
        }
        return Math.min(1.0, (double) sum / samples / Short.MAX_VALUE);
    }

    /**
     * Stops audio capture and releases the microphone.
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
        if (line != null && line.isOpen()) {
            line.stop();
            line.close();
        }
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running.get();
    }

    public AudioDevice getDevice() {
        return device;
    }

    public BitrateConfig getConfig() {
        return config;
    }
}
