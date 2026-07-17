package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.MediaKind;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.SdpType;
import io.github.kinsleykajiva.webrtc.TransceiverDirection;
import io.github.kinsleykajiva.webrtc.WebRtc;

/**
 * Demonstrates the standard WebRTC Java API: create a peer connection, add an audio
 * transceiver, create an SDP offer, set it as the local description, and create a
 * data channel.
 */
public class Main {

    public static void main(String[] args) {
        WebRtc.initialize();
        System.out.println("Probe: " + WebRtc.probe());
        System.out.flush();

        Configuration config = Configuration.create()
                .addIceServer("stun:stun.l.google.com:19302");
        System.out.println("Config created");
        System.out.flush();

        PeerConnection pc = PeerConnection.create(config, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                System.out.println("Local ICE candidate: " + candidate);
            }

            @Override
            public void onConnectionStateChange(
                    io.github.kinsleykajiva.webrtc.PeerConnectionState state) {
                System.out.println("Connection state: " + state);
            }
        });

        // Add audio + video transceivers (recvonly so we don't need local tracks).
        pc.addTransceiver(MediaKind.AUDIO, TransceiverDirection.RECV_ONLY);
        pc.addTransceiver(MediaKind.VIDEO, TransceiverDirection.RECV_ONLY);

        int dcId = pc.createDataChannel("demo", true);
        System.out.println("Created data channel id=" + dcId);

        SessionDescription offer = pc.createOffer();
        pc.setLocalDescription(offer);

        System.out.println("Local SDP type: " + offer.getSdpType());
        System.out.println("--- SDP (first lines) ---");
        String sdp = offer.getSdp();
        sdp.lines().limit(6).forEach(System.out::println);
        System.out.println("... (total " + sdp.lines().count() + " lines)");

        // Show we can build a remote description from the generated SDP text too.
        SessionDescription parsed = SessionDescription.create(SdpType.OFFER, sdp);
        System.out.println("Re-parsed SDP type: " + parsed.getSdpType());
        parsed.close();

        pc.close();
        System.out.println("Done.");
    }
}
