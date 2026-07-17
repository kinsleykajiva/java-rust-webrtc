package io.github.kinsleykajiva.webrtc;

import io.github.kinsleykajiva.webrtc.ffm.webrtc_ffi_h;
import io.github.kinsleykajiva.webrtc.internal.NativeLibraryLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Entry point and low-level helpers for the native Rust WebRTC implementation.
 *
 * <p>Most applications should use {@link PeerConnection}, {@link Configuration},
 * {@link SessionDescription} and the related types directly. This class also exposes
 * {@link #initialize()} to eagerly load the native library.</p>
 */
public final class WebRtc {

    private WebRtc() {}

    /** Ensures the native shared library is loaded into this process. */
    public static void initialize() {
        NativeLibraryLoader.load();
    }

    /**
     * Performs a minimal FFI round-trip with the native layer to confirm the
     * bridge is operational.
     */
    public static String probe() {
        initialize();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ptr = webrtc_ffi_h.webrtc_ffi_init();
            if (ptr == null || ptr.address() == 0) {
                throw new IllegalStateException("Native webrtc_ffi_init returned null");
            }
            String result = ptr.reinterpret(Long.MAX_VALUE).getString(0L);
            webrtc_ffi_h.webrtc_ffi_free_string(ptr);
            return result;
        }
    }
}
