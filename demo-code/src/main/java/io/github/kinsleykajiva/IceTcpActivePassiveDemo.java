package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.DataChannel;
import io.github.kinsleykajiva.webrtc.DtlsRole;
import io.github.kinsleykajiva.webrtc.NetworkType;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.PeerConnectionState;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demonstrates ICE-over-TCP with an active (offerer) and passive (answerer) peer
 * running in the same process over the loopback interface.
 *
 * <p>Both peers are configured for TCP-only gathering. The offerer uses the default
 * DTLS role (actpass/auto) and the answerer forces {@link DtlsRole#CLIENT}. ICE
 * candidates are exchanged in-process through the {@link PeerConnection.Observer}
 * callbacks, after which a data channel is opened and a message is sent end-to-end.</p>
 */
public class IceTcpActivePassiveDemo {

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        final CountDownLatch connected = new CountDownLatch(2);
        final AtomicReference<String> received = new AtomicReference<>();
        // Holders resolve the mutual reference between the two peers' observers.
        final PeerConnection[] offererRef = new PeerConnection[1];
        final PeerConnection[] answererRef = new PeerConnection[1];

        // ---- Passive (answerer) peer: listens on a TCP address, DTLS client ----
        Configuration answererCfg = Configuration.create()
                .useTcpOnly("127.0.0.1:8443", DtlsRole.CLIENT);

        PeerConnection answerer = PeerConnection.create(answererCfg, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                // Forward this candidate to the offerer.
                if (offererRef[0] != null) {
                    offererRef[0].addIceCandidate(candidate, sdpMid, 0);
                }
            }

            @Override
            public void onConnectionStateChange(PeerConnectionState state) {
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }

            @Override
            public void onDataChannel(int id, String label) {
                answererRef[0].setDataChannelCallbacks(id,
                        (dcId, data) -> {
                            received.set(new String(data));
                            System.out.println("[Answer] received: " + received.get());
                        },
                        dcId -> System.out.println("[Answer] data channel open"),
                        dcId -> System.out.println("[Answer] data channel closed"));
            }
        });
        answererRef[0] = answerer;

        // ---- Active (offerer) peer: ephemeral TCP, default DTLS role ----
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
                if (state == PeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
            }
        });
        offererRef[0] = offerer;

        // Create a data channel on the offerer side.
        int dcId = offerer.createDataChannel("data", true);
        offerer.setDataChannelCallbacks(dcId,
                (id, data) -> System.out.println("[Offer] received: " + new String(data)),
                id -> System.out.println("[Offer] data channel open"),
                id -> System.out.println("[Offer] data channel closed"));

        // Offer / answer exchange.
        SessionDescription offer = offerer.createOffer();
        offerer.setLocalDescription(offer);
        answerer.setRemoteDescription(offer);

        SessionDescription answer = answerer.createAnswer();
        answerer.setLocalDescription(answer);
        offerer.setRemoteDescription(answer);

        System.out.println("Signaling complete; gathering TCP candidates and connecting...");
        boolean ok = connected.await(20, TimeUnit.SECONDS);
        System.out.println("Connected: " + ok);

        if (ok) {
            // Give the answerer's data channel time to open before sending.
            Thread.sleep(2000);
            offerer.sendDataChannelText(dcId, "hello over TCP!");
            Thread.sleep(2000);
            System.out.println("Round-trip received by answerer: " + (received.get() != null));
        }

        offerer.close();
        answerer.close();
        System.out.println("Done.");
    }
}
