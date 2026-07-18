package io.github.kinsleykajiva.webrtc;

/**
 * Describes a single RTP codec supported by the underlying native WebRTC implementation.
 *
 * <p>Retrieve the list of all registered codecs via {@link WebRtc#listSupportedCodecs()}.
 * Use {@link MimeTypes} constants for the {@code mimeType} field values.</p>
 *
 * @param kind        {@code audio} or {@code video}
 * @param mimeType    IANA media subtype, e.g. {@link MimeTypes#AUDIO_OPUS} or {@link MimeTypes#VIDEO_VP8}
 * @param clockRate   RTP clock rate in Hz
 * @param channels    number of audio channels (0 for video codecs)
 * @param payloadType static RTP payload type assigned by the media engine
 * @param fmtp        SDP format parameters (a=fmtp line), possibly empty
 */
public record Codec(String kind, String mimeType, long clockRate, int channels, int payloadType, String fmtp) {

    static Codec parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 6) {
            return null;
        }
        long clockRate = f[2].isEmpty() ? 0 : Long.parseLong(f[2]);
        int channels = f[3].isEmpty() ? 0 : Integer.parseInt(f[3]);
        int payloadType = Integer.parseInt(f[4]);
        return new Codec(f[0], f[1], clockRate, channels, payloadType, f[5]);
    }
}
