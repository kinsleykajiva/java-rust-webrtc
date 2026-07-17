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

    /**
     * Returns the full list of RTP codecs supported by the native implementation.
     *
     * <p>The set is derived from the media engine's default codec registry (audio and
     * video), including RTX repair streams and FEC codecs where registered.</p>
     */
    public static java.util.List<Codec> listSupportedCodecs() {
        initialize();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ptr = webrtc_ffi_h.webrtc_ffi_supported_codecs();
            if (ptr == null || ptr.address() == 0) {
                return java.util.List.of();
            }
            String raw = ptr.reinterpret(Long.MAX_VALUE).getString(0L);
            webrtc_ffi_h.webrtc_ffi_free_string(ptr);
            java.util.List<Codec> codecs = new java.util.ArrayList<>();
            for (String line : raw.split("\n", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                Codec c = Codec.parse(line);
                if (c != null) {
                    codecs.add(c);
                }
            }
            return java.util.List.copyOf(codecs);
        }
    }
}
