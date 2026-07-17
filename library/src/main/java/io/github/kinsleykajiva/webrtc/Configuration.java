package io.github.kinsleykajiva.webrtc;

import io.github.kinsleykajiva.webrtc.ffm.webrtc_ffi_h;
import io.github.kinsleykajiva.webrtc.internal.NativeLibraryLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the native configuration (ICE servers, etc.) for a {@link PeerConnection}.
 *
 * <p>Ownership of the underlying native handle is transferred to {@link PeerConnection}
 * when {@link PeerConnection#create(Configuration, PeerConnection.Observer)} is called;
 * afterwards the configuration must not be reused.</p>
 */
public final class Configuration implements AutoCloseable {

    private final MemorySegment handle;
    private boolean closed;

    private Configuration(MemorySegment handle) {
        this.handle = handle;
    }

    /** Creates an empty configuration. */
    public static Configuration create() {
        NativeLibraryLoader.load();
        MemorySegment h = webrtc_ffi_h.webrtc_ffi_config_create();
        if (h == null || h.address() == 0) {
            throw new IllegalStateException("Failed to create native configuration");
        }
        return new Configuration(h);
    }

    /** Adds a STUN/TURN server. {@code urls} may contain multiple entries separated by commas. */
    public Configuration addIceServer(String urls) {
        return addIceServer(urls, null, null);
    }

    /** Adds a STUN/TURN server with optional TURN credentials. */
    public Configuration addIceServer(String urls, String username, String credential) {
        checkClosed();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment u = str(arena, urls);
            MemorySegment user = str(arena, username);
            MemorySegment cred = str(arena, credential);
            int rc = webrtc_ffi_h.webrtc_ffi_config_add_ice_server(handle, u, user, cred);
            if (rc != 0) {
                throw new IllegalArgumentException("Invalid ICE server configuration: " + rc);
            }
        }
        return this;
    }

    /**
     * Configures the transport layer.
     *
     * @param udpAddrs   space/comma separated UDP listen addresses, or {@code null}/empty
     *                   for the default {@code 0.0.0.0:0}. Pass an explicit empty string to
     *                   disable UDP (combined with TCP addrs this yields TCP-only gathering).
     * @param tcpAddrs   space/comma separated TCP listen addresses (RFC 4571), or
     *                   {@code null}/empty to disable TCP.
     * @param dtlsRole   one of {@link DtlsRole} (e.g. {@link DtlsRole#CLIENT} for the answerer).
     * @param networkTypes bitmask of {@link NetworkType} values (0 = library defaults).
     */
    public Configuration setTransport(String udpAddrs, String tcpAddrs, int dtlsRole, int networkTypes) {
        checkClosed();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment udp = str(arena, udpAddrs);
            MemorySegment tcp = str(arena, tcpAddrs);
            int rc = webrtc_ffi_h.webrtc_ffi_config_set_transport(handle, udp, tcp, dtlsRole, networkTypes);
            if (rc != 0) {
                throw new IllegalArgumentException("Invalid transport configuration: " + rc);
            }
        }
        return this;
    }

    /** Forces TCP-only candidate gathering on the given TCP listen address. */
    public Configuration useTcpOnly(String tcpAddr, DtlsRole dtlsRole) {
        return setTransport("", tcpAddr, dtlsRole.value, NetworkType.TCP.value);
    }

    /** Forces TCP-only candidate gathering with both UDP and TCP network types enabled. */
    public Configuration useNetworkTypes(int networkTypes) {
        return setTransport(null, null, 0, networkTypes);
    }

    private static MemorySegment str(Arena arena, String s) {
        if (s == null) {
            return MemorySegment.NULL;
        }
        return arena.allocateFrom(s);
    }

    MemorySegment handle() {
        return handle;
    }

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("Configuration already consumed/closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            webrtc_ffi_h.webrtc_ffi_config_free(handle);
            closed = true;
        }
    }
}
