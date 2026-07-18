package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.DataChannel;
import io.github.kinsleykajiva.webrtc.DtlsRole;
import io.github.kinsleykajiva.webrtc.NetworkType;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SdpType;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.StatsReport;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the Stats API by creating two peers over UDP, exchanging a data
 * channel message, and then fetching stats from both peer connections.
 */
public class StatsDemo {

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        final CountDownLatch connected = new CountDownLatch(2);
        final PeerConnection[] offererRef = new PeerConnection[1];
        final PeerConnection[] answererRef = new PeerConnection[1];

        Configuration offererCfg = Configuration.create()
                .setTransport("", "127.0.0.1:0", 0, NetworkType.TCP.value);

        PeerConnection offerer = PeerConnection.create(offererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (answererRef[0] != null) {
                    answererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[offerer] state=" + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }

            @Override
            public void onDataChannel(int id, String label) {
                System.out.println("[offerer] remote data channel id=" + id + " label=" + label);
            }
        });
        offererRef[0] = offerer;

        Configuration answererCfg = Configuration.create()
                .useTcpOnly("127.0.0.1:8443", DtlsRole.CLIENT);

        PeerConnection answerer = PeerConnection.create(answererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                if (offererRef[0] != null) {
                    offererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                System.out.println("[answerer] state=" + state);
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }

            @Override
            public void onDataChannel(int id, String label) {
                System.out.println("[answerer] remote data channel id=" + id + " label=" + label);
            }
        });
        answererRef[0] = answerer;

        int dcId = offerer.createDataChannel("stats-test", true);
        offerer.setDataChannelCallbacks(dcId,
                (id, data) -> System.out.println("[offerer] received: " + new String(data)),
                (id) -> System.out.println("[offerer] dc open"),
                (id) -> System.out.println("[offerer] dc close"));

        var offer = offerer.createOffer();
        String offerSdp = offer.getSdp();
        offerer.setLocalDescription(offer);
        answerer.setRemoteDescription(SessionDescription.create(SdpType.OFFER, offerSdp));
        var answer = answerer.createAnswer();
        String answerSdp = answer.getSdp();
        answerer.setLocalDescription(answer);
        offerer.setRemoteDescription(SessionDescription.create(SdpType.ANSWER, answerSdp));

        System.out.println("Waiting for connection...");
        if (!connected.await(10, TimeUnit.SECONDS)) {
            System.out.println("Timeout waiting for connection");
        }

        Thread.sleep(1000);

        offerer.sendDataChannelText(dcId, "hello from offerer");

        Thread.sleep(1000);

        System.out.println("\n=== Stats from OFFERER ===");
        StatsReport offererStats = offerer.getStats();
        for (var pc : offererStats.peerConnections()) {
            System.out.println("  PeerConnection: opened=" + pc.dataChannelsOpened() + " closed=" + pc.dataChannelsClosed());
        }
        for (var ir : offererStats.inboundRtpStreams()) {
            System.out.println("  InboundRTP: ssrc=" + ir.ssrc() + " kind=" + ir.kind()
                    + " bytes=" + ir.bytesReceived() + " packets=" + ir.packetsReceived()
                    + " lost=" + ir.packetsLost());
        }

        System.out.println("\n=== Stats from ANSWERER ===");
        StatsReport answererStats = answerer.getStats();
        for (var pc : answererStats.peerConnections()) {
            System.out.println("  PeerConnection: opened=" + pc.dataChannelsOpened() + " closed=" + pc.dataChannelsClosed());
        }
        for (var ir : answererStats.inboundRtpStreams()) {
            System.out.println("  InboundRTP: ssrc=" + ir.ssrc() + " kind=" + ir.kind()
                    + " bytes=" + ir.bytesReceived() + " packets=" + ir.packetsReceived()
                    + " lost=" + ir.packetsLost());
        }

        offerer.close();
        answerer.close();
        System.out.println("\nDone.");
    }
}
