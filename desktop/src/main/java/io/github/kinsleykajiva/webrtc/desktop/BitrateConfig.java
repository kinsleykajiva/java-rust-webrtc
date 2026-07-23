package io.github.kinsleykajiva.webrtc.desktop;

/**
 * Configuration for audio and video encoding parameters.
 *
 * <p>Controls the quality, bandwidth, and resource usage of captured media.
 * Higher bitrates and frame rates produce better quality but use more bandwidth
 * and CPU.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * BitrateConfig config = BitrateConfig.defaults();
 * config.setAudioBitrate(64000);     // 64 kbps audio
 * config.setVideoBitrate(1_500_000); // 1.5 Mbps video
 * config.setVideoFps(30);            // 30 fps
 * }</pre>
 *
 * <h2>Recommended presets</h2>
 * <table>
 *   <tr><th>Preset</th><th>Audio</th><th>Video</th><th>FPS</th><th>Use case</th></tr>
 *   <tr><td>Low bandwidth</td><td>32 kbps</td><td>300 kbps</td><td>15</td><td>Mobile, poor network</td></tr>
 *   <tr><td>Default</td><td>64 kbps</td><td>1.5 Mbps</td><td>30</td><td>Desktop, good network</td></tr>
 *   <tr><td>High quality</td><td>128 kbps</td><td>4 Mbps</td><td>30</td><td>LAN, high quality</td></tr>
 *   <tr><td>Screen share</td><td>64 kbps</td><td>2 Mbps</td><td>15</td><td>Desktop sharing</td></tr>
 * </table>
 */
public class BitrateConfig {

    /** Audio bitrate in bits per second. Default: 64000 (64 kbps). */
    private int audioBitrate = 64_000;

    /** Audio sample rate in Hz. Default: 48000 (48 kHz, Opus standard). */
    private int audioSampleRate = 48_000;

    /** Number of audio channels. Default: 1 (mono). */
    private int audioChannels = 1;

    /** Audio bits per sample. Default: 16. */
    private int audioBitsPerSample = 16;

    /** Video bitrate in bits per second. Default: 1_500_000 (1.5 Mbps). */
    private int videoBitrate = 1_500_000;

    /** Video capture width in pixels. Default: 640. */
    private int videoWidth = 640;

    /** Video capture height in pixels. Default: 480. */
    private int videoHeight = 480;

    /** Video frames per second. Default: 30. */
    private int videoFps = 30;

    public BitrateConfig() {}

    // ── Audio ──────────────────────────────────────────────────────────────

    public int getAudioBitrate() { return audioBitrate; }
    public void setAudioBitrate(int audioBitrate) { this.audioBitrate = audioBitrate; }

    public int getAudioSampleRate() { return audioSampleRate; }
    public void setAudioSampleRate(int audioSampleRate) { this.audioSampleRate = audioSampleRate; }

    public int getAudioChannels() { return audioChannels; }
    public void setAudioChannels(int audioChannels) { this.audioChannels = audioChannels; }

    public int getAudioBitsPerSample() { return audioBitsPerSample; }
    public void setAudioBitsPerSample(int audioBitsPerSample) { this.audioBitsPerSample = audioBitsPerSample; }

    /** Returns the audio frame size in bytes (one sample frame = channels * bytesPerSample). */
    public int audioFrameSize() {
        return audioChannels * (audioBitsPerSample / 8);
    }

    /** Returns the number of bytes per second of raw audio. */
    public int audioBytesPerSecond() {
        return audioSampleRate * audioFrameSize();
    }

    // ── Video ──────────────────────────────────────────────────────────────

    public int getVideoBitrate() { return videoBitrate; }
    public void setVideoBitrate(int videoBitrate) { this.videoBitrate = videoBitrate; }

    public int getVideoWidth() { return videoWidth; }
    public void setVideoWidth(int videoWidth) { this.videoWidth = videoWidth; }

    public int getVideoHeight() { return videoHeight; }
    public void setVideoHeight(int videoHeight) { this.videoHeight = videoHeight; }

    public int getVideoFps() { return videoFps; }
    public void setVideoFps(int videoFps) { this.videoFps = videoFps; }

    // ── Presets ────────────────────────────────────────────────────────────

    /**
     * Returns a default configuration suitable for most desktop use cases.
     */
    public static BitrateConfig defaults() {
        return new BitrateConfig();
    }

    /**
     * Returns a low-bandwidth configuration for constrained networks.
     */
    public static BitrateConfig lowBandwidth() {
        BitrateConfig c = new BitrateConfig();
        c.setAudioBitrate(32_000);
        c.setVideoBitrate(300_000);
        c.setVideoWidth(320);
        c.setVideoHeight(240);
        c.setVideoFps(15);
        return c;
    }

    /**
     * Returns a high-quality configuration for LAN or fast connections.
     */
    public static BitrateConfig highQuality() {
        BitrateConfig c = new BitrateConfig();
        c.setAudioBitrate(128_000);
        c.setVideoBitrate(4_000_000);
        c.setVideoWidth(1280);
        c.setVideoHeight(720);
        c.setVideoFps(30);
        return c;
    }

    /**
     * Returns a screen-sharing configuration (lower fps, higher resolution).
     */
    public static BitrateConfig screenShare() {
        BitrateConfig c = new BitrateConfig();
        c.setAudioBitrate(64_000);
        c.setVideoBitrate(2_000_000);
        c.setVideoWidth(1920);
        c.setVideoHeight(1080);
        c.setVideoFps(15);
        return c;
    }

    @Override
    public String toString() {
        return String.format("BitrateConfig{audio=%dkbps %dHz %dch, video=%dx%d@%dfps %dkbps}",
            audioBitrate / 1000, audioSampleRate, audioChannels,
            videoWidth, videoHeight, videoFps, videoBitrate / 1000);
    }
}
