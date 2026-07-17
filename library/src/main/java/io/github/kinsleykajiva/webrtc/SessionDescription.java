package io.github.kinsleykajiva.webrtc;

import io.github.kinsleykajiva.webrtc.ffm.webrtc_ffi_h;
import io.github.kinsleykajiva.webrtc.internal.NativeLibraryLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * An SDP session description (offer/answer). Wraps a native description handle.
 *
 * <p>The native handle is consumed by {@code setLocalDescription}/{@code setRemoteDescription}
 * and must not be used afterwards.</p>
 */
public final class SessionDescription implements AutoCloseable {

    private final MemorySegment handle;

    SessionDescription(MemorySegment handle) {
        this.handle = handle;
    }

    /** Creates a description from raw SDP type and SDP text. */
    public static SessionDescription create(SdpType type, String sdp) {
        NativeLibraryLoader.load();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sdpSeg = arena.allocateFrom(sdp);
            MemorySegment h = webrtc_ffi_h.webrtc_ffi_description_create(type.value, sdpSeg);
            if (h == null || h.address() == 0) {
                throw new IllegalArgumentException("Failed to create SessionDescription");
            }
            return new SessionDescription(h);
        }
    }

    /** Returns the SDP text. */
    public String getSdp() {
        if (handle == null || handle.address() == 0) {
            return "";
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ptr = webrtc_ffi_h.webrtc_ffi_description_sdp(handle);
            if (ptr == null || ptr.address() == 0) {
                return "";
            }
            String s = ptr.reinterpret(Long.MAX_VALUE).getString(0L);
            webrtc_ffi_h.webrtc_ffi_free_string(ptr);
            return s;
        }
    }

    /** Returns the SDP type. */
    public SdpType getSdpType() {
        if (handle == null || handle.address() == 0) {
            return SdpType.UNSPECIFIED;
        }
        return SdpType.fromValue(webrtc_ffi_h.webrtc_ffi_description_type(handle));
    }

    MemorySegment handle() {
        return handle;
    }

    @Override
    public void close() {
        if (handle != null && handle.address() != 0) {
            webrtc_ffi_h.webrtc_ffi_description_free(handle);
        }
    }
}
