package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Codec;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.util.List;

/**
 * Prints the full list of RTP codecs supported by the native WebRTC implementation,
 * grouped by media kind.
 */
public class SupportedCodecs {

    public static void main(String[] args) {
        WebRtc.initialize();

        List<Codec> codecs = WebRtc.listSupportedCodecs();
        if (codecs.isEmpty()) {
            System.out.println("No supported codecs reported.");
            return;
        }

        long audio = codecs.stream().filter(c -> "audio".equals(c.kind())).count();
        long video = codecs.stream().filter(c -> "video".equals(c.kind())).count();

        System.out.println("Supported codecs: " + codecs.size() + " (audio=" + audio
                + ", video=" + video + ")");
        System.out.println();

        printGroup(codecs, "audio");
        printGroup(codecs, "video");
    }

    private static void printGroup(List<Codec> codecs, String kind) {
        System.out.println("=== " + kind.toUpperCase() + " ===");
        System.out.printf("%-22s %-10s %-8s %-5s %s%n", "MIME", "Clock", "Ch", "PT", "FMTP");
        for (Codec c : codecs) {
            if (!kind.equals(c.kind())) {
                continue;
            }
            String ch = c.channels() == 0 ? "-" : String.valueOf(c.channels());
            System.out.printf(
                    "%-22s %-10d %-8s %-5d %s%n",
                    c.mimeType(), c.clockRate(), ch, c.payloadType(), c.fmtp());
        }
        System.out.println();
    }
}
