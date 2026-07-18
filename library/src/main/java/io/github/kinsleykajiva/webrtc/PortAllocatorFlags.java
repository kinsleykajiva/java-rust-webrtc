package io.github.kinsleykajiva.webrtc;

/**
 * Bitmask constants controlling which ICE candidate types are gathered.
 *
 * <p>Pass a bitwise OR of these flags to {@link Configuration#setAllocatorFlags(int)}
 * to enable or disable specific candidate gathering behavior. These flags mirror
 * WebRTC's native {@code PortAllocator} flags.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Configuration cfg = Configuration.create();
 * // Disable UDP candidates, enable IPv6
 * cfg.setAllocatorFlags(PortAllocatorFlags.DISABLE_UDP | PortAllocatorFlags.ENABLE_IPV6);
 *
 * // Disable relay (TURN) candidates
 * cfg.setAllocatorFlags(PortAllocatorFlags.DISABLE_RELAY);
 *
 * // Combine with port range
 * cfg.setPortRange(10000, 20000);
 * cfg.setAllocatorFlags(PortAllocatorFlags.DISABLE_TCP);
 * }</pre>
 *
 * <h2>Flag reference</h2>
 * <table>
 *   <tr><th>Constant</th><th>Value</th><th>Description</th></tr>
 *   <tr><td>{@link #DISABLE_UDP}</td><td>1</td><td>Disable local UDP socket allocation for host candidates</td></tr>
 *   <tr><td>{@link #DISABLE_STUN}</td><td>2</td><td>Disable STUN candidate gathering (server reflexive)</td></tr>
 *   <tr><td>{@link #DISABLE_RELAY}</td><td>4</td><td>Disable TURN relay candidate gathering</td></tr>
 *   <tr><td>{@link #DISABLE_TCP}</td><td>8</td><td>Disable local TCP candidate gathering</td></tr>
 *   <tr><td>{@link #ENABLE_IPV6}</td><td>16</td><td>Enable IPv6 support</td></tr>
 *   <tr><td>{@link #ENABLE_SHARED_SOCKET}</td><td>32</td><td>Enable shared UDP socket mode</td></tr>
 *   <tr><td>{@link #ENABLE_STUN_RETRANSMIT_ATTRIBUTE}</td><td>64</td><td>Include STUN retransmit attribute on requests</td></tr>
 *   <tr><td>{@link #DISABLE_ADAPTER_ENUMERATION}</td><td>128</td><td>Do not enumerate network adapters</td></tr>
 *   <tr><td>{@link #DISABLE_DEFAULT_LOCAL_CANDIDATE}</td><td>256</td><td>Do not generate a default local candidate</td></tr>
 *   <tr><td>{@link #DISABLE_UDP_RELAY}</td><td>512</td><td>Disable UDP TURN relay</td></tr>
 *   <tr><td>{@link #DISABLE_COSTLY_NETWORKS}</td><td>1024</td><td>Avoid cellular/expensive networks</td></tr>
 *   <tr><td>{@link #ENABLE_IPV6_ON_WIFI}</td><td>2048</td><td>Allow IPv6 over Wi-Fi</td></tr>
 *   <tr><td>{@link #ENABLE_ANY_ADDRESS_PORTS}</td><td>4096</td><td>Allow binding to any-address (0.0.0.0) ports</td></tr>
 *   <tr><td>{@link #DISABLE_LINK_LOCAL_NETWORKS}</td><td>8192</td><td>Avoid link-local network interfaces</td></tr>
 * </table>
 *
 * <h2>Notes</h2>
 * <ul>
 *   <li>Pass {@code 0} (or don't call {@code setAllocatorFlags}) to use all native defaults.</li>
 *   <li>Multiple flags can be combined with bitwise OR: {@code DISABLE_UDP | DISABLE_TCP}.</li>
 *   <li>See {@link Configuration#setPortRange(int, int)} for port range control.</li>
 *   <li>See {@link MimeTypes} for supported audio/video codec content.</li>
 * </ul>
 */
public final class PortAllocatorFlags {

    private PortAllocatorFlags() {}

    /** Disable local UDP socket allocation for host candidates. */
    public static final int DISABLE_UDP = 1;

    /** Disable STUN candidate gathering (server reflexive). */
    public static final int DISABLE_STUN = 2;

    /** Disable TURN relay candidate gathering. */
    public static final int DISABLE_RELAY = 4;

    /** Disable local TCP candidate gathering. */
    public static final int DISABLE_TCP = 8;

    /** Enable IPv6 support. */
    public static final int ENABLE_IPV6 = 16;

    /** Enable shared UDP socket mode (platform/stack-dependent behavior). */
    public static final int ENABLE_SHARED_SOCKET = 32;

    /** Include STUN retransmit attribute on requests. */
    public static final int ENABLE_STUN_RETRANSMIT_ATTRIBUTE = 64;

    /** Do not enumerate network adapters. */
    public static final int DISABLE_ADAPTER_ENUMERATION = 128;

    /** Do not generate a default local candidate. */
    public static final int DISABLE_DEFAULT_LOCAL_CANDIDATE = 256;

    /** Disable UDP TURN relay. */
    public static final int DISABLE_UDP_RELAY = 512;

    /** Avoid cellular/expensive networks for candidate gathering. */
    public static final int DISABLE_COSTLY_NETWORKS = 1024;

    /** Allow IPv6 over Wi-Fi. */
    public static final int ENABLE_IPV6_ON_WIFI = 2048;

    /** Allow binding to any-address (0.0.0.0) ports. */
    public static final int ENABLE_ANY_ADDRESS_PORTS = 4096;

    /** Avoid link-local network interfaces. */
    public static final int DISABLE_LINK_LOCAL_NETWORKS = 8192;
}
