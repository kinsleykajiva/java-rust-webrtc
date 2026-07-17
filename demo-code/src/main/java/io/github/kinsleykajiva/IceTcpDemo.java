package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.DtlsRole;
import io.github.kinsleykajiva.webrtc.NetworkType;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.WebRtc;

/**
 * Demonstrates pure ICE-over-TCP connectivity setup.
 *
 * <p>The peer is configured with {@link Configuration#useTcpOnly} which disables UDP
 * gathering and listens for TCP (RFC 4571) connections only, with the DTLS answering
 * role set to {@link DtlsRole#CLIENT} (typical for the answerer side).</p>
 */
public class IceTcpDemo {

    public static void main(String[] args) {
        WebRtc.initialize();

        String tcpAddr = "127.0.0.1:8443";
        Configuration config = Configuration.create()
                .useTcpOnly(tcpAddr, DtlsRole.CLIENT);

        PeerConnection pc = PeerConnection.create(config, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                // TCP candidates are reported here (proto tcp).
                if (candidate.contains("tcp")) {
                    System.out.println("TCP ICE candidate: " + candidate);
                }
            }

            @Override
            public void onConnectionStateChange(
                    io.github.kinsleykajiva.webrtc.PeerConnectionState state) {
                System.out.println("Connection state: " + state);
            }
        });

        System.out.println("Listening for ICE TCP connections on " + tcpAddr);
        System.out.println("Network types: TCP only, DTLS role: CLIENT");
        System.out.println();

        // Generate an offer so candidates are gathered over TCP.
        SessionDescription offer = pc.createOffer();
        pc.setLocalDescription(offer);
        String sdp = offer.getSdp();

        System.out.println("=== Local SDP (TCP candidates) ===");
        for (String line : sdp.split("\n")) {
            if (line.startsWith("a=candidate") && line.contains("tcp")) {
                System.out.println(line);
            }
        }
        System.out.println("(non-TCP candidates are absent because UDP was disabled)");
        offer.close();

        pc.close();
        System.out.println("Done.");
    }
}
