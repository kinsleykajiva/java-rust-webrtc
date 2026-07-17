package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.WebRtc;

/**
 * Demonstrates ICE restart: a single {@link PeerConnection} generates an initial
 * offer, then a second offer with fresh ICE credentials. The printed SDP shows the
 * ICE username-fragment / password lines change between the two offers, which is the
 * essence of an ICE restart (forces re-gathering and re-connectivity checks).
 */
public class IceRestartDemo {

    public static void main(String[] args) {
        WebRtc.initialize();

        Configuration config = Configuration.create()
                .addIceServer("stun:stun.l.google.com:19302");

        PeerConnection pc = PeerConnection.create(config, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                System.out.println("ICE candidate: " + candidate);
            }

            @Override
            public void onConnectionStateChange(
                    io.github.kinsleykajiva.webrtc.PeerConnectionState state) {
                System.out.println("Connection state: " + state);
            }
        });

        // A data channel is required for the offer to carry ICE credentials.
        pc.createDataChannel("debug", true);

        // Initial offer.
        SessionDescription offer1 = pc.createOffer();
        pc.setLocalDescription(offer1);
        String sdp1 = offer1.getSdp();
        System.out.println("=== Initial offer (ICE credentials) ===");
        printIceLines(sdp1);
        offer1.close();

        // ICE restart: generate a fresh offer with new credentials.
        SessionDescription offer2 = pc.createOffer(true);
        pc.setLocalDescription(offer2);
        String sdp2 = offer2.getSdp();
        System.out.println("=== Restarted offer (new ICE credentials) ===");
        printIceLines(sdp2);
        offer2.close();

        boolean changed = !extractIce(sdp1).equals(extractIce(sdp2));
        System.out.println();
        System.out.println("ICE credentials changed after restart: " + changed);

        pc.close();
        System.out.println("Done.");
    }

    private static void printIceLines(String sdp) {
        for (String line : sdp.split("\n")) {
            if (line.startsWith("a=ice-ufrag") || line.startsWith("a=ice-pwd")) {
                System.out.println(line);
            }
        }
    }

    private static String extractIce(String sdp) {
        StringBuilder sb = new StringBuilder();
        for (String line : sdp.split("\n")) {
            if (line.startsWith("a=ice-ufrag") || line.startsWith("a=ice-pwd")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
