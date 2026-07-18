package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.DataChannel;
import io.github.kinsleykajiva.webrtc.IceGatheringState;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.MimeTypes;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demonstrates Trickle ICE — the standard mechanism where ICE candidates are sent
 * as they are discovered, rather than waiting for all candidates to be gathered
 * before sending the SDP offer/answer.
 *
 * <h2>What is Trickle ICE?</h2>
 * <p>In non-trickle ICE, the peer waits until ALL candidates (host, srflx, relay)
 * are gathered, embeds them in the SDP, and then sends the offer/answer. This
 * delays connection setup by seconds or minutes.</p>
 *
 * <p>In <b>Trickle ICE</b>, the SDP is sent immediately (without candidates), and
 * each candidate is sent separately via the signaling channel as it is discovered.
 * This dramatically reduces connection setup time.</p>
 *
 * <h2>ICE Candidate Types</h2>
 * <table>
 *   <tr><th>Type</th><th>Source</th><th>Description</th></tr>
 *   <tr><td><b>host</b></td><td>Local interface</td><td>Direct local network address (LAN IP). Always available.</td></tr>
 *   <tr><td><b>srflx</b></td><td>STUN server</td><td>Server-reflexive address (public IP seen by STUN server). Requires STUN.</td></tr>
 *   <tr><td><b>relay</b></td><td>TURN server</td><td>Relay address through TURN server. Required when both peers are behind symmetric NATs.</td></tr>
 * </table>
 *
 * <h2>Four Modes</h2>
 * <p>This demo supports 4 ICE modes via command-line argument:</p>
 * <pre>
 *   java TrickleIceDemo [mode]
 *
 *   Modes:
 *     host     - Host candidates only (no STUN/TURN servers)
 *     srflx    - Host + STUN server-reflexive candidates
 *     relay    - Relay candidates only (TURN server, relay policy)
 *     combined - Host + srflx + relay (STUN + TURN servers)
 * </pre>
 *
 * <h2>Trickle ICE Flow</h2>
 * <pre>
 *   Offerer                          Answerer
 *      |                                |
 *      |-- createOffer() -------------->|
 *      |-- setLocalDescription() ------>|    (SDP sent WITHOUT candidates)
 *      |                                |
 *      |<-- setRemoteDescription() -----|
 *      |<-- createAnswer() -------------|
 *      |<-- setLocalDescription() ------|
 *      |                                |
 *      |  (ICE gathering starts)        |  (ICE gathering starts)
 *      |                                |
 *      |<-- onIceCandidate(Host1) ------|    ← trickled immediately
 *      |-- addIceCandidate(Host1) ----->|
 *      |                                |
 *      |-- onIceCandidate(Host2) ------>|    ← trickled immediately
 *      |<-- addIceCandidate(Host2) -----|
 *      |                                |
 *      |<-- onIceCandidate(srflx) ------|    ← trickled when STUN responds
 *      |-- addIceCandidate(srflx) ----->|
 *      |                                |
 *      |-- onIceCandidate(srflx) ------>|
 *      |<-- addIceCandidate(srflx) -----|
 *      |                                |
 *      |  (Gathering complete)          |  (Gathering complete)
 *      |                                |
 *      |<== Connectivity Check ========>|
 *      |<== Data Channel ==============>|
 * </pre>
 *
 * <h2>Supported Audio Codecs</h2>
 * <p>The library supports 4 audio codecs for transcoding scenarios (see {@link AudioTranscoderDemo}):</p>
 * <ul>
 *   <li>{@link MimeTypes#AUDIO_OPUS} — 48 kHz, wideband (speech + music)</li>
 *   <li>{@link MimeTypes#AUDIO_G722} — 8 kHz, wideband telephony</li>
 *   <li>{@link MimeTypes#AUDIO_PCMU} — 8 kHz, G.711 mu-law</li>
 *   <li>{@link MimeTypes#AUDIO_PCMA} — 8 kHz, G.711 A-law</li>
 * </ul>
 *
 * <h2>STUN/TURN Server Setup</h2>
 * <p>For srflx and relay modes, you need running STUN/TURN servers:</p>
 * <pre>
 *   # Public STUN server (no installation needed)
 *   stun:stun.l.google.com:19302
 *
 *   # Local TURN server (using pion/turn)
 *   turnserver --realm=webrtc.rs --user=user:pass --port=3478
 *
 *   # Docker TURN server
 *   docker run -p 3478:3478/udp \
 *     -e REALM=webrtc.rs \
 *     -e USER=user=pass \
 *     instrumentisto/coturn
 * </pre>
 */
public class TrickleIceDemo {

    private static final String STUN_SERVER = "stun:stun.l.google.com:19302";
    private static final String TURN_SERVER = "turn:127.0.0.1:3478?transport=udp";
    private static final String TURN_USER = "user";
    private static final String TURN_CREDENTIAL = "pass";

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "host";
        WebRtc.initialize();

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║              Trickle ICE Demo                       ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        printModeInfo(mode);
        System.out.println();

        // ── State ───────────────────────────────────────────────────────────────
        final CountDownLatch connected = new CountDownLatch(2);
        final AtomicInteger offererCandidates = new AtomicInteger(0);
        final AtomicInteger answererCandidates = new AtomicInteger(0);
        final AtomicInteger offererGatheringTransitions = new AtomicInteger(0);
        final AtomicInteger answererGatheringTransitions = new AtomicInteger(0);
        final AtomicReference<IceGatheringState> offererGathering = new AtomicReference<>(IceGatheringState.NEW);
        final AtomicReference<IceGatheringState> answererGathering = new AtomicReference<>(IceGatheringState.NEW);
        final AtomicReference<String> dcReceived = new AtomicReference<>();

        final PeerConnection[] offererRef = new PeerConnection[1];
        final PeerConnection[] answererRef = new PeerConnection[1];

        // ── Build configuration for the chosen mode ─────────────────────────────
        Configuration offererCfg = buildConfig(mode);
        Configuration answererCfg = buildConfig(mode);

        // ── Answerer peer ───────────────────────────────────────────────────────
        PeerConnection answerer = PeerConnection.create(answererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                int count = answererCandidates.incrementAndGet();
                System.out.printf("[Answerer]  ICE candidate #%d: %s%n", count, candidate.substring(0, Math.min(80, candidate.length())));
                if (offererRef != null) {
                    offererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onIceGatheringStateChange(IceGatheringState state) {
                IceGatheringState prev = answererGathering.getAndSet(state);
                answererGatheringTransitions.incrementAndGet();
                System.out.printf("[Answerer]  Gathering: %s → %s%n", prev, state);
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Answerer]  state: " + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }

            @Override
            public void onDataChannel(int id, String label) {
                answererRef[0].setDataChannelCallbacks(id,
                        (dcId, data) -> {
                            dcReceived.set(new String(data));
                            System.out.println("[Answerer]  DC received: " + dcReceived.get());
                        },
                        dcId -> System.out.println("[Answerer]  DC open"),
                        dcId -> System.out.println("[Answerer]  DC closed"));
            }
        });
        answererRef[0] = answerer;

        // ── Offerer peer ────────────────────────────────────────────────────────
        PeerConnection offerer = PeerConnection.create(offererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                int count = offererCandidates.incrementAndGet();
                System.out.printf("[Offerer]   ICE candidate #%d: %s%n", count, candidate.substring(0, Math.min(80, candidate.length())));
                if (answererRef[0] != null) {
                    answererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onIceGatheringStateChange(IceGatheringState state) {
                IceGatheringState prev = offererGathering.getAndSet(state);
                offererGatheringTransitions.incrementAndGet();
                System.out.printf("[Offerer]   Gathering: %s → %s%n", prev, state);
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[Offerer]   state: " + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }
        });
        offererRef[0] = offerer;

        // ── Create data channel (offerer side) ──────────────────────────────────
        int dcId = offerer.createDataChannel("trickle-test", true);
        offerer.setDataChannelCallbacks(dcId,
                (id, data) -> System.out.println("[Offerer]   DC received: " + new String(data)),
                id -> System.out.println("[Offerer]   DC open"),
                id -> System.out.println("[Offerer]   DC closed"));

        // ── SDP exchange (TRICKLE ICE: send immediately, no waiting) ────────────
        System.out.println("[Signal]    Creating offer (candidates NOT yet gathered)...");
        SessionDescription offer = offerer.createOffer();
        offerer.setLocalDescription(offer);
        System.out.println("[Signal]    Offer created and set locally");
        System.out.println("[Signal]    Sending offer to answerer (no candidates embedded)");

        answerer.setRemoteDescription(offer);

        SessionDescription answer = answerer.createAnswer();
        answerer.setLocalDescription(answer);
        System.out.println("[Signal]    Answer created and set locally");
        System.out.println("[Signal]    Sending answer to offerer (no candidates embedded)");
        System.out.println();

        // ── SDP exchange complete — candidates will trickle from here ────────────
        System.out.println("SDP exchange complete. ICE candidates will trickle as gathered...");
        System.out.println();

        // ── Wait for connection ──────────────────────────────────────────────────
        boolean ok = connected.await(20, TimeUnit.SECONDS);
        System.out.println();

        if (ok) {
            // Give data channel time to open.
            Thread.sleep(1000);
            offerer.sendDataChannelText(dcId, "Hello via Trickle ICE!");
            Thread.sleep(1000);
        }

        // ── Report ──────────────────────────────────────────────────────────────
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║                   Results                          ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.printf("║  Mode:              %-32s ║%n", mode);
        System.out.printf("║  Connected:         %-32s ║%n", ok);
        System.out.printf("║  Offerer candidates:%-32d ║%n", offererCandidates.get());
        System.out.printf("║  Answerer candidates:%-31d ║%n", answererCandidates.get());
        System.out.printf("║  Offerer gathering: %-32s ║%n", offererGathering.get());
        System.out.printf("║  Answerer gathering:%-32s ║%n", answererGathering.get());
        System.out.printf("║  DC received:       %-32s ║%n", dcReceived.get());
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.println("║  Candidate types:                                   ║");
        System.out.println("║    host   - local network interface (always avail)  ║");
        System.out.println("║    srflx  - STUN server-reflexive (needs STUN)      ║");
        System.out.println("║    relay  - TURN relay (needs TURN server)          ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.println("║  ICE Gathering States:                              ║");
        System.out.println("║    NEW       → not started                          ║");
        System.out.println("║    GATHERING → collecting candidates                ║");
        System.out.println("║    COMPLETE  → all candidates gathered              ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        offerer.close();
        answerer.close();
        System.out.println("Done.");
    }

    /**
     * Builds a {@link Configuration} for the specified ICE mode.
     *
     * <table>
     *   <tr><th>Mode</th><th>ICE Servers</th><th>Candidate Types</th></tr>
     *   <tr><td>{@code host}</td><td>None</td><td>Host only (local interface IPs)</td></tr>
     *   <tr><td>{@code srflx}</td><td>STUN server</td><td>Host + Server-reflexive</td></tr>
     *   <tr><td>{@code relay}</td><td>TURN server</td><td>Relay only (TURN allocation)</td></tr>
     *   <tr><td>{@code combined}</td><td>STUN + TURN servers</td><td>Host + srflx + relay</td></tr>
     * </table>
     */
    private static Configuration buildConfig(String mode) {
        Configuration cfg = Configuration.create();
        switch (mode) {
            case "host":
                // No ICE servers — only local host candidates.
                break;
            case "srflx":
                // STUN server for server-reflexive candidates.
                cfg.addIceServer(STUN_SERVER);
                break;
            case "relay":
                // TURN server for relay candidates.
                cfg.addIceServer(TURN_SERVER, TURN_USER, TURN_CREDENTIAL);
                break;
            case "combined":
                // Both STUN and TURN servers.
                cfg.addIceServer(STUN_SERVER);
                cfg.addIceServer(TURN_SERVER, TURN_USER, TURN_CREDENTIAL);
                break;
            default:
                System.err.println("Unknown mode: " + mode);
                System.err.println("Valid modes: host, srflx, relay, combined");
                System.exit(1);
        }
        return cfg;
    }

    private static void printModeInfo(String mode) {
        System.out.printf("Mode: %s%n", mode);
        switch (mode) {
            case "host":
                System.out.println("  ICE servers: none");
                System.out.println("  Candidates:  host (local network addresses only)");
                System.out.println("  Use case:    peers on the same LAN, no NAT traversal needed");
                System.out.println("  Note:        Always works in loopback / same-machine tests");
                break;
            case "srflx":
                System.out.println("  ICE servers: " + STUN_SERVER);
                System.out.println("  Candidates:  host + srflx (server-reflexive)");
                System.out.println("  Use case:    peers behind NAT with cone mapping (non-symmetric)");
                System.out.println("  Requirement: STUN server must be reachable from both peers");
                break;
            case "relay":
                System.out.println("  ICE servers: " + TURN_SERVER);
                System.out.println("  Candidates:  relay (TURN allocation only)");
                System.out.println("  Use case:    both peers behind symmetric NAT, or privacy mode");
                System.out.println("  Requirement: TURN server with valid credentials");
                break;
            case "combined":
                System.out.println("  ICE servers: " + STUN_SERVER + " + " + TURN_SERVER);
                System.out.println("  Candidates:  host + srflx + relay (all types)");
                System.out.println("  Use case:    production — try host first, fall back to srflx, then relay");
                System.out.println("  Note:        ICE automatically selects the best candidate pair");
                break;
        }
    }
}
