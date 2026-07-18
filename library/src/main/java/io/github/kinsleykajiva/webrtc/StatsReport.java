package io.github.kinsleykajiva.webrtc;

import io.github.kinsleykajiva.webrtc.ffm.webrtc_ffi_h;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

public final class StatsReport {

    public record InboundRtpStats(
            long ssrc,
            String kind,
            String trackIdentifier,
            long bytesReceived,
            long packetsReceived,
            long packetsLost,
            double jitter,
            int nackCount,
            int firCount,
            int pliCount
    ) {}

    public record PeerConnectionStats(
            int dataChannelsOpened,
            int dataChannelsClosed
    ) {}

    public record RemoteCandidateStats(
            String address,
            int port,
            String candidateType
    ) {}

    private final List<InboundRtpStats> inboundRtpStreams = new ArrayList<>();
    private final List<PeerConnectionStats> peerConnections = new ArrayList<>();
    private final List<RemoteCandidateStats> remoteCandidates = new ArrayList<>();

    private StatsReport() {}

    public List<InboundRtpStats> inboundRtpStreams() { return inboundRtpStreams; }
    public List<PeerConnectionStats> peerConnections() { return peerConnections; }
    public List<RemoteCandidateStats> remoteCandidates() { return remoteCandidates; }

    public static StatsReport fetch(PeerConnection pc) {
        MemorySegment jsonPtr = webrtc_ffi_h.webrtc_ffi_get_stats(pc.handle());
        String json = readStr(jsonPtr);
        return parse(json);
    }

    private static StatsReport parse(String json) {
        StatsReport report = new StatsReport();
        if (json == null || json.isEmpty()) return report;
        String trimmed = json.strip();
        if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("]")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.isEmpty()) return report;

        String[] objects = trimmed.split("\\}\\s*,\\s*\\{");
        for (int i = 0; i < objects.length; i++) {
            String obj = objects[i].strip();
            if (!obj.startsWith("{")) obj = "{" + obj;
            if (!obj.endsWith("}")) obj = obj + "}";

            String type = extractString(obj, "type");
            if (type == null) continue;

            switch (type) {
                case "inbound-rtp" -> report.inboundRtpStreams.add(new InboundRtpStats(
                        extractLong(obj, "ssrc"),
                        extractString(obj, "kind"),
                        extractString(obj, "track_identifier"),
                        extractLong(obj, "bytes_received"),
                        extractLong(obj, "packets_received"),
                        extractLong(obj, "packets_lost"),
                        extractDouble(obj, "jitter"),
                        extractInt(obj, "nack_count"),
                        extractInt(obj, "fir_count"),
                        extractInt(obj, "pli_count")
                ));
                case "peer-connection" -> report.peerConnections.add(new PeerConnectionStats(
                        extractInt(obj, "data_channels_opened"),
                        extractInt(obj, "data_channels_closed")
                ));
                case "remote-candidate" -> report.remoteCandidates.add(new RemoteCandidateStats(
                        extractString(obj, "address"),
                        extractInt(obj, "port"),
                        extractString(obj, "candidate_type")
                ));
            }
        }
        return report;
    }

    private static String extractString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        idx = json.indexOf(':', idx + pattern.length());
        if (idx < 0) return null;
        int start = json.indexOf('"', idx + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static long extractLong(String json, String key) {
        String val = extractRaw(json, key);
        if (val == null || val.isEmpty()) return 0;
        try { return Long.parseLong(val); } catch (NumberFormatException e) { return 0; }
    }

    private static int extractInt(String json, String key) {
        String val = extractRaw(json, key);
        if (val == null || val.isEmpty()) return 0;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return 0; }
    }

    private static double extractDouble(String json, String key) {
        String val = extractRaw(json, key);
        if (val == null || val.isEmpty()) return 0;
        try { return Double.parseDouble(val); } catch (NumberFormatException e) { return 0; }
    }

    private static String extractRaw(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        idx = json.indexOf(':', idx + pattern.length());
        if (idx < 0) return null;
        int start = idx + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        String val = json.substring(start, end).strip();
        if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
        return val;
    }

    private static String readStr(MemorySegment ptr) {
        if (ptr == null || ptr.address() == 0) return "";
        return ptr.reinterpret(Long.MAX_VALUE).getString(0L);
    }
}
