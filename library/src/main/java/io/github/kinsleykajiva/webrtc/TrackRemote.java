package io.github.kinsleykajiva.webrtc;

import io.github.kinsleykajiva.webrtc.ffm.webrtc_ffi_h;
import io.github.kinsleykajiva.webrtc.ffm.webrtc_ffi_track_remote_set_callbacks$on_rtp;
import io.github.kinsleykajiva.webrtc.ffm.webrtc_ffi_track_remote_set_callbacks$on_open;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;

public final class TrackRemote {

    public interface RtpCallback {
        void onRtpPacket(int trackId, byte[] payload, int payloadType, int sequenceNumber, int timestamp, int ssrc);
    }

    public interface OpenCallback {
        void onOpen(int trackId, int ssrc, String rid);
    }

    private static final ConcurrentHashMap<Integer, TrackRemote> TRACKS = new ConcurrentHashMap<>();

    private final int nativeTrackId;
    private RtpCallback rtpCallback;
    private OpenCallback openCallback;

    private TrackRemote(int nativeTrackId) {
        this.nativeTrackId = nativeTrackId;
    }

    public void setRtpCallback(RtpCallback cb) {
        this.rtpCallback = cb;
    }

    public void setOpenCallback(OpenCallback cb) {
        this.openCallback = cb;
    }

    public String getSsrcs() {
        return readStr(webrtc_ffi_h.webrtc_ffi_track_remote_ssrcs(nativeTrackId));
    }

    public String getCodec(int ssrc) {
        return readStr(webrtc_ffi_h.webrtc_ffi_track_remote_codec(nativeTrackId, ssrc));
    }

    public int getKind() {
        return webrtc_ffi_h.webrtc_ffi_track_remote_kind(nativeTrackId);
    }

    public String getRid(int ssrc) {
        return readStr(webrtc_ffi_h.webrtc_ffi_track_remote_rid(nativeTrackId, ssrc));
    }

    public boolean writeRtcpPli(int mediaSsrc) {
        return webrtc_ffi_h.webrtc_ffi_track_remote_write_rtcp(nativeTrackId, mediaSsrc) != 0;
    }

    public String getTrackId() {
        return readStr(webrtc_ffi_h.webrtc_ffi_track_remote_id(nativeTrackId));
    }

    public String getLabel() {
        return readStr(webrtc_ffi_h.webrtc_ffi_track_remote_label(nativeTrackId));
    }

    public int getNativeTrackId() {
        return nativeTrackId;
    }

    static TrackRemote register(int nativeTrackId) {
        return TRACKS.computeIfAbsent(nativeTrackId, id -> {
            TrackRemote track = new TrackRemote(id);
            webrtc_ffi_h.webrtc_ffi_track_remote_set_callbacks(id, ON_RTP, ON_OPEN);
            return track;
        });
    }

    private static final webrtc_ffi_track_remote_set_callbacks$on_rtp.Function ON_RTP_FUNC = (trackId, payloadPtr, len, payloadType, seq, ts, ssrc) -> {
        byte[] payload = new byte[(int) len];
        if (payloadPtr != null && payloadPtr.address() != 0) {
            MemorySegment.copy(payloadPtr.reinterpret(len), ValueLayout.JAVA_BYTE, 0, payload, 0, (int) len);
        }
        TrackRemote t = TRACKS.get(trackId);
        if (t != null && t.rtpCallback != null) {
            t.rtpCallback.onRtpPacket(trackId, payload, payloadType & 0xFF, seq & 0xFFFF, ts, ssrc);
        }
    };

    private static final webrtc_ffi_track_remote_set_callbacks$on_open.Function ON_OPEN_FUNC = (trackId, ssrc, ridPtr) -> {
        TrackRemote t = TRACKS.get(trackId);
        if (t != null && t.openCallback != null) {
            t.openCallback.onOpen(trackId, ssrc, readStr(ridPtr));
        }
    };

    private static final MemorySegment ON_RTP;
    private static final MemorySegment ON_OPEN;

    static {
        Arena arena = Arena.ofShared();
        ON_RTP = webrtc_ffi_track_remote_set_callbacks$on_rtp.allocate(ON_RTP_FUNC, arena);
        ON_OPEN = webrtc_ffi_track_remote_set_callbacks$on_open.allocate(ON_OPEN_FUNC, arena);
    }

    private static String readStr(MemorySegment ptr) {
        if (ptr == null || ptr.address() == 0) return "";
        return ptr.reinterpret(Long.MAX_VALUE).getString(0L);
    }
}
