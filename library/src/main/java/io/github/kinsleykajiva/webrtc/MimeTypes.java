package io.github.kinsleykajiva.webrtc;

/**
 * Canonical MIME type constants for every codec registered by the native WebRTC media engine.
 *
 * <p>Use these constants wherever a MIME type string is required — track creation,
 * transceiver configuration, codec negotiation, etc. — instead of hardcoding raw
 * strings.</p>
 *
 * <h2>Supported codecs</h2>
 * <table>
 *   <tr><th>Constant</th><th>MIME Type</th><th>Kind</th><th>Clock Rate</th><th>Content format</th></tr>
 *   <tr><td>{@link #AUDIO_OPUS}</td><td>{@code audio/opus}</td><td>audio</td><td>48000</td><td>OGG/Opus container</td></tr>
 *   <tr><td>{@link #AUDIO_G722}</td><td>{@code audio/G722}</td><td>audio</td><td>8000</td><td>Raw G.722 frames</td></tr>
 *   <tr><td>{@link #AUDIO_PCMU}</td><td>{@code audio/PCMU}</td><td>audio</td><td>8000</td><td>Raw G.711 mu-law PCM</td></tr>
 *   <tr><td>{@link #AUDIO_PCMA}</td><td>{@code audio/PCMA}</td><td>audio</td><td>8000</td><td>Raw G.711 A-law PCM</td></tr>
 *   <tr><td>{@link #AUDIO_TELEPHONE_EVENT}</td><td>{@code audio/telephone-event}</td><td>audio</td><td>—</td><td>RFC 4733 DTMF events</td></tr>
 *   <tr><td>{@link #VIDEO_VP8}</td><td>{@code video/VP8}</td><td>video</td><td>90000</td><td>IVF container</td></tr>
 *   <tr><td>{@link #VIDEO_VP9}</td><td>{@code video/VP9}</td><td>video</td><td>90000</td><td>IVF container</td></tr>
 *   <tr><td>{@link #VIDEO_H264}</td><td>{@code video/H264}</td><td>video</td><td>90000</td><td>Raw Annex B bitstream</td></tr>
 *   <tr><td>{@link #VIDEO_H265}</td><td>{@code video/H265}</td><td>video</td><td>90000</td><td>Raw Annex B bitstream</td></tr>
 *   <tr><td>{@link #VIDEO_AV1}</td><td>{@code video/AV1}</td><td>video</td><td>90000</td><td>IVF container</td></tr>
 *   <tr><td>{@link #VIDEO_RTX}</td><td>{@code video/rtx}</td><td>video</td><td>90000</td><td>RTX retransmission</td></tr>
 *   <tr><td>{@link #VIDEO_ULP_FEC}</td><td>{@code video/ulpfec}</td><td>video</td><td>90000</td><td>UlpForward Error Correction</td></tr>
 * </table>
 *
 * <h2>Demo content</h2>
 * <p>Sample media files for testing are located in the {@code demo-content/} directory at the
 * project root. Each file can be passed directly to the corresponding reader class:</p>
 * <pre>
 * // VP8 video from IVF
 * try (var reader = new IvfReader(new FileInputStream("demo-content/output_vp8.ivf"))) { ... }
 *
 * // VP9 video from IVF
 * try (var reader = new IvfReader(new FileInputStream("demo-content/output_vp9.ivf"))) { ... }
 *
 * // H.264 video from raw Annex B bitstream
 * try (var reader = new H26xReader(new FileInputStream("demo-content/output.264"), 1024*1024, false)) { ... }
 *
 * // H.265 (HEVC) video from raw Annex B bitstream
 * try (var reader = new H26xReader(new FileInputStream("demo-content/output.265"), 1024*1024, true)) { ... }
 *
 * // Opus audio from OGG container
 * try (var reader = new OggReader(new FileInputStream("demo-content/output.ogg"))) { ... }
 * </pre>
 *
 * <p>For codecs without a bundled sample (AV1, G.722, PCMU, PCMA, MP3, WAV) you can generate
 * content with {@code ffmpeg}. Examples:</p>
 * <pre>
 * # AV1 IVF (video only)
 * ffmpeg -i input.mp4 -c:v libaom-av1 -crf 30 demo-content/output_av1.ivf
 *
 * # G.722 raw frames
 * ffmpeg -i input.wav -c:a g722 -f g722 demo-content/output_g722
 *
 * # PCMU (G.711 mu-law) raw
 * ffmpeg -i input.wav -c:a pcm_mulaw -f mulaw demo-content/output_pcmu
 *
 * # PCMA (G.711 A-law) raw
 * ffmpeg -i input.wav -c:a pcm_alaw -f alaw demo-content/output_pcma
 *
 * # MP3
 * ffmpeg -i input.wav -codec:a libmp3lame -b:a 128k demo-content/output.mp3
 *
 * # WAV (PCM)
 * ffmpeg -i input.ogg -c:a pcm_s16le -ar 48000 demo-content/output.wav
 * </pre>
 *
 * <h2>Content format details</h2>
 * <ul>
 *   <li><b>IVF</b> — Read with {@link IvfReader}. Used for VP8, VP9, and AV1 video.</li>
 *   <li><b>OGG/Opus</b> — Read with {@link OggReader}. Used for Opus audio.</li>
 *   <li><b>Raw Annex B</b> — Read with {@link H26xReader}. Used for H.264 and H.265 video.
 *       Pass {@code isHevc=false} for H.264, {@code isHevc=true} for H.265.</li>
 *   <li><b>MP3</b> — Read with {@link Mp3Reader}. MPEG-2 Layer III frames.</li>
 *   <li><b>WAV</b> — Read with {@link WavReader}. RIFF WAVE with PCM (audio format 1) only.</li>
 *   <li><b>Raw PCM</b> — No dedicated reader. Feed bytes directly via
 *       {@link TrackLocal#createRtpTrack} for RTP forwarding.</li>
 * </ul>
 *
 * <h2>RTP forwarding (no reader needed)</h2>
 * <p>For pre-packetized RTP from external sources (e.g. ffmpeg UDP output, IP cameras, SRT
 * gateways), use {@link TrackLocal#createRtpTrack} and {@link TrackLocal#writeRtp} directly.
 * See {@code RtpToWebRtcDemo} for a complete example.</p>
 */
public final class MimeTypes {

    private MimeTypes() {}

    // ─── Audio codecs ──────────────────────────────────────────────────────────

    /**
     * Opus audio codec ({@code audio/opus}).
     *
     * <p>Wideband speech and music codec. Clock rate 48 000 Hz. Default payload type 111.</p>
     * <p>Content: OGG/Opus container ({@link OggReader}). Use {@code demo-content/output.ogg}.</p>
     */
    public static final String AUDIO_OPUS = "audio/opus";

    /**
     * G.722 audio codec ({@code audio/G722}).
     *
     * <p>Wideband telephony codec. Clock rate 8 000 Hz (samples transmitted at 16 kHz).
     * Default payload type 9.</p>
     * <p>Content: raw G.722 frames. Generate with
     * {@code ffmpeg -i input.wav -c:a g722 -f g722 output}.</p>
     */
    public static final String AUDIO_G722 = "audio/G722";

    /**
     * G.711 mu-law audio codec ({@code audio/PCMU}).
     *
     * <p>Narrowband telephony codec. Clock rate 8 000 Hz. Default payload type 0.</p>
     * <p>Content: raw mu-law PCM. Generate with
     * {@code ffmpeg -i input.wav -c:a pcm_mulaw -f mulaw output}.</p>
     */
    public static final String AUDIO_PCMU = "audio/PCMU";

    /**
     * G.711 A-law audio codec ({@code audio/PCMA}).
     *
     * <p>Narrowband telephony codec. Clock rate 8 000 Hz. Default payload type 8.</p>
     * <p>Content: raw A-law PCM. Generate with
     * {@code ffmpeg -i input.wav -c:a pcm_alaw -f alaw output}.</p>
     */
    public static final String AUDIO_PCMA = "audio/PCMA";

    /**
     * Telephone-event codec ({@code audio/telephone-event}).
     *
     * <p>RFC 4733 DTMF / touch-tone events. Not registered by default in the media engine.</p>
     */
    public static final String AUDIO_TELEPHONE_EVENT = "audio/telephone-event";

    // ─── Video codecs ──────────────────────────────────────────────────────────

    /**
     * VP8 video codec ({@code video/VP8}).
     *
     * <p>Google's open-source royalty-free video codec. Clock rate 90 000 Hz.
     * Default payload type 96.</p>
     * <p>Content: IVF container ({@link IvfReader}). Use {@code demo-content/output_vp8.ivf}.</p>
     */
    public static final String VIDEO_VP8 = "video/VP8";

    /**
     * VP9 video codec ({@code video/VP9}).
     *
     * <p>Google's next-gen open-source video codec. Clock rate 90 000 Hz.
     * Default payload types 98 (profile 0) and 100 (profile 1).</p>
     * <p>Content: IVF container ({@link IvfReader}). Use {@code demo-content/output_vp9.ivf}.</p>
     */
    public static final String VIDEO_VP9 = "video/VP9";

    /**
     * H.264 / AVC video codec ({@code video/H264}).
     *
     * <p>ITU-T H.264 / ISO MPEG-4 AVC. Clock rate 90 000 Hz. Multiple payload types
     * depending on profile/level (102, 127, 125, 108, 123).</p>
     * <p>Content: raw Annex B bitstream ({@link H26xReader} with {@code isHevc=false}).
     * Use {@code demo-content/output.264}.</p>
     * <p>Typical SDP fmtp: {@code level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f}</p>
     */
    public static final String VIDEO_H264 = "video/H264";

    /**
     * H.265 / HEVC video codec ({@code video/H265}).
     *
     * <p>ITU-T H.265 / ISO MPEG-H HEVC. Clock rate 90 000 Hz. Default payload type 126.</p>
     * <p>Content: raw Annex B bitstream ({@link H26xReader} with {@code isHevc=true}).
     * Use {@code demo-content/output.265}.</p>
     */
    public static final String VIDEO_H265 = "video/H265";

    /**
     * AV1 video codec ({@code video/AV1}).
     *
     * <p>Alliance for Open Media's next-gen royalty-free video codec. Clock rate 90 000 Hz.
     * Default payload type 41.</p>
     * <p>Content: IVF container ({@link IvfReader}). Generate with
     * {@code ffmpeg -i input.mp4 -c:v libaom-av1 -crf 30 output.ivf}.</p>
     */
    public static final String VIDEO_AV1 = "video/AV1";

    /**
     * RTX retransmission codec ({@code video/rtx}).
     *
     * <p>RFC 4588 RTP retransmission. Clock rate 90 000 Hz. Paired with each primary video codec.
     * Payload types 97, 99, 101, 103, 105, 109, 124, 106, 107.</p>
     */
    public static final String VIDEO_RTX = "video/rtx";

    /**
     * UlpForward Error Correction codec ({@code video/ulpfec}).
     *
     * <p>RFC 5109 Uneven Level Protection FEC. Clock rate 90 000 Hz. Default payload type 116.</p>
     */
    public static final String VIDEO_ULP_FEC = "video/ulpfec";

    /**
     * Flex Forward Error Correction codec ({@code video/flexfec}).
     *
     * <p>RFC 8627 Flexible FEC. Not registered by default.</p>
     */
    public static final String VIDEO_FLEX_FEC = "video/flexfec";

    /**
     * Flex Forward Error Correction 03 codec ({@code video/flexfec-03}).
     *
     * <p>Draft-03 variant of Flexible FEC. Not registered by default.</p>
     */
    public static final String VIDEO_FLEX_FEC03 = "video/flexfec-03";

    // ─── Demo content file names ───────────────────────────────────────────────

    /**
     * Path to the VP8 sample video file ({@code demo-content/output_vp8.ivf}).
     * <p>Use with {@link IvfReader} and {@link #VIDEO_VP8}.</p>
     */
    public static final String CONTENT_VP8 = "demo-content/output_vp8.ivf";

    /**
     * Path to the VP9 sample video file ({@code demo-content/output_vp9.ivf}).
     * <p>Use with {@link IvfReader} and {@link #VIDEO_VP9}.</p>
     */
    public static final String CONTENT_VP9 = "demo-content/output_vp9.ivf";

    /**
     * Path to the H.264 sample video file ({@code demo-content/output.264}).
     * <p>Use with {@link H26xReader} ({@code isHevc=false}) and {@link #VIDEO_H264}.</p>
     */
    public static final String CONTENT_H264 = "demo-content/output.264";

    /**
     * Path to the H.265 (HEVC) sample video file ({@code demo-content/output.265}).
     * <p>Use with {@link H26xReader} ({@code isHevc=true}) and {@link #VIDEO_H265}.</p>
     */
    public static final String CONTENT_H265 = "demo-content/output.265";

    /**
     * Path to the Opus sample audio file ({@code demo-content/output.ogg}).
     * <p>Use with {@link OggReader} and {@link #AUDIO_OPUS}.</p>
     */
    public static final String CONTENT_OPUS = "demo-content/output.ogg";

    /**
     * Path to the Opus playlist sample audio file ({@code demo-content/playlist.ogg}).
     * <p>Use with {@link OggReader} and {@link #AUDIO_OPUS}. Contains multiple tracks
     * for playlist switching demos.</p>
     */
    public static final String CONTENT_PLAYLIST = "demo-content/playlist.ogg";
}
