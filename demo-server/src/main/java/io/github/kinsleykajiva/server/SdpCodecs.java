package io.github.kinsleykajiva.server;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, dependency-free SDP helpers used to discover the negotiated codec so
 * the echo track can be created with the exact mime/clock the client is sending,
 * and so the looped-back RTP packets carry the payload type the browser expects.
 */
public final class SdpCodecs {

    private SdpCodecs() {
    }

    public record Codec(String mime, int clock, int pt) {
        public Codec(String mime, int clock) {
            this(mime, clock, 0);
        }
    }

    private static final Pattern RTPMAP = Pattern.compile("a=rtpmap:(\\d+)\\s+([^/]+)/(\\d+)");

    /** Codec names that are FEC/repair/telephony and not actual media. */
    private static final java.util.Set<String> NON_MEDIA =
            java.util.Set.of("red", "rtx", "ulpfec", "flexfec", "telephone-event");

    /** Returns the first audio codec declared in the SDP, or {@code null}. */
    public static Codec firstAudioCodec(String sdp) {
        return firstInMedia(sdp, "audio", false);
    }

    /** Returns the first video codec declared in the SDP, or {@code null}. */
    public static Codec firstVideoCodec(String sdp) {
        return firstInMedia(sdp, "video", false);
    }

    /**
     * Returns the first *real* (non-FEC/RTX) codec in a media section, including
     * its payload type. This is the codec the answerer's media engine prefers,
     * which is what the offerer (browser) will actually send.
     */
    public static Codec firstMediaCodec(String sdp, String kind) {
        return firstInMedia(sdp, kind, true);
    }

    private static Codec firstInMedia(String sdp, String kind, boolean skipNonMedia) {
        String section = mediaSection(sdp, kind);
        if (section == null) return null;
        Matcher m = RTPMAP.matcher(section);
        while (m.find()) {
            String name = m.group(2);
            if (skipNonMedia && NON_MEDIA.contains(name.toLowerCase())) continue;
            int clock = Integer.parseInt(m.group(3));
            int pt = Integer.parseInt(m.group(1));
            return new Codec(kind + "/" + name, clock, pt);
        }
        return null;
    }

    /**
     * Finds the dynamic payload type assigned to {@code mime} (e.g. {@code video/VP8})
     * within the SDP. Used to stamp the loopback RTP packets correctly.
     */
    public static Integer payloadTypeForMime(String sdp, String mime) {
        String subtype = mime.contains("/") ? mime.substring(mime.indexOf('/') + 1) : mime;
        String section = mediaSection(sdp, mime.startsWith("audio") ? "audio" : "video");
        if (section == null) return null;
        Matcher m = RTPMAP.matcher(section);
        while (m.find()) {
            if (m.group(2).equalsIgnoreCase(subtype)) {
                return Integer.parseInt(m.group(1));
            }
        }
        return null;
    }

    /**
     * Returns the payload type of the FIRST codec declared in a media section.
     * The client sends RTP using its offer's payload types, so this is the PT we
     * expect to see on inbound packets (used to classify audio vs video).
     */
    public static Integer firstPayloadType(String sdp, String kind) {
        String section = mediaSection(sdp, kind);
        if (section == null) return null;
        Matcher m = RTPMAP.matcher(section);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }

    private static String mediaSection(String sdp, String kind) {
        String[] lines = sdp.split("\r?\n");
        StringBuilder sb = null;
        for (String line : lines) {
            if (line.startsWith("m=")) {
                if (line.startsWith("m=" + kind)) {
                    sb = new StringBuilder();
                    sb.append(line).append('\n');
                } else if (sb != null) {
                    // Reached the next media section; the one we wanted is complete.
                    break;
                } else {
                    sb = null;
                }
            } else if (sb != null) {
                sb.append(line).append('\n');
            }
        }
        return sb == null ? null : sb.toString();
    }
}
