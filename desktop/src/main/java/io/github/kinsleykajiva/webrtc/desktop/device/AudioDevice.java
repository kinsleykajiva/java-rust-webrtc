package io.github.kinsleykajiva.webrtc.desktop.device;

/**
 * Represents an audio device (microphone or speaker) available on the system.
 *
 * <p>Audio devices are enumerated from the Java Sound API mixers. Each device
 * wraps a {@code javax.sound.sampled.Mixer.Info} and provides a clean API
 * for selecting audio input (microphone) or output (speaker) devices.</p>
 *
 * @param id       unique identifier (mixer name)
 * @param name     human-readable display name
 * @param type     INPUT for microphones, OUTPUT for speakers
 * @param sampleRate   default sample rate in Hz (e.g. 44100, 48000)
 * @param channels     number of audio channels (1 = mono, 2 = stereo)
 * @param bitsPerSample bits per sample (8, 16, 24)
 */
public record AudioDevice(
    String id,
    String name,
    DeviceType type,
    int sampleRate,
    int channels,
    int bitsPerSample
) {
    public enum DeviceType { INPUT, OUTPUT }

    @Override
    public String toString() {
        return name + " (" + type + ", " + sampleRate + "Hz, " + channels + "ch, " + bitsPerSample + "bit)";
    }
}
