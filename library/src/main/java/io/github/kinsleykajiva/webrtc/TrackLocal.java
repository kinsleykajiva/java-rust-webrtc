package io.github.kinsleykajiva.webrtc;

import io.github.kinsleykajiva.webrtc.ffm.webrtc_ffi_h;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A local media track for sending samples (audio or video) over a {@link PeerConnection}.
 *
 * <p>Created via {@link #create(MediaKind, String, String, String, int, String, int, int, String)},
 * added via {@link PeerConnection#addTrack(TrackLocal)}, and written to via {@link #writeSample}.</p>
 */
public final class TrackLocal {

    private final int nativeTrackId;
    private final int ssrc;

    private TrackLocal(int nativeTrackId, int ssrc) {
        this.nativeTrackId = nativeTrackId;
        this.ssrc = ssrc;
    }

    /** Returns the SSRC used at creation time. */
    public int ssrc() {
        return ssrc;
    }

    /** Returns the native track handle id. */
    public int nativeTrackId() {
        return nativeTrackId;
    }

    /**
     * Creates a local track for the given codec configuration.
     *
     * @param kind        audio or video
     * @param streamId    media stream identifier
     * @param trackId     track identifier
     * @param label       human-readable label
     * @param ssrc        synchronization source id (use 0 for random)
     * @param mimeType    e.g. "audio/opus", "video/H264", "video/VP8"
     * @param clockRate   e.g. 48000 for Opus, 90000 for video
     * @param channels    0 for video, 2 for stereo audio
     * @param sdpFmtpLine codec fmtp line, may be empty
     * @return the new track, or {@code null} on failure
     */
    public static TrackLocal create(
            MediaKind kind,
            String streamId,
            String trackId,
            String label,
            int ssrc,
            String mimeType,
            int clockRate,
            int channels,
            String sdpFmtpLine) {
        if (ssrc == 0) {
            ssrc = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sId = arena.allocateFrom(streamId);
            MemorySegment tId = arena.allocateFrom(trackId);
            MemorySegment lbl = arena.allocateFrom(label);
            MemorySegment mime = arena.allocateFrom(mimeType);
            MemorySegment fmtp = sdpFmtpLine == null ? MemorySegment.NULL : arena.allocateFrom(sdpFmtpLine);
            int handle = webrtc_ffi_h.webrtc_ffi_create_track_local(
                    sId, tId, lbl, kind.value, ssrc, mime, clockRate, channels, fmtp);
            if (handle == 0) {
                return null;
            }
            return new TrackLocal(handle, ssrc);
        }
    }

    /**
     * Writes a media sample (raw codec bitstream) to this track.
     *
     * @param payloadType the negotiated payload type from the sender
     * @param data        raw codec frame bytes
     * @param durationMs  sample duration in milliseconds
     */
    public void writeSample(int payloadType, byte[] data, int durationMs) {
        writeSample(nativeTrackId, ssrc, payloadType, data, durationMs);
    }

    /**
     * Writes a media sample with an explicit SSRC and payload type.
     */
    public static void writeSample(int trackId, int ssrc, int payloadType, byte[] data, int durationMs) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocateFrom(ValueLayout.JAVA_BYTE, data);
            webrtc_ffi_h.webrtc_ffi_write_sample(
                    trackId, ssrc, (byte) payloadType, seg, data.length, durationMs);
        }
    }

    /** Returns a random SSRC value suitable for track creation. */
    public static int randomSsrc() {
        return ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
    }
}
