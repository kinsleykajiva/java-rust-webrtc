package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Mirrors the Rust {@code data-channels-simple} example: a data channel is opened and a
 * greeting is exchanged between the two peers. In the Rust version one peer is a
 * browser; here both peers run in a single process over loopback for a self-contained
 * demo.
 */
public class DataChannelsSimpleDemo {

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        // Counts down once each side has received the other's greeting.
        CountDownLatch received = new CountDownLatch(2);

        final PeerPair[] pair = new PeerPair[1];

        PeerConnection.Observer offererObs = new PeerConnection.Observer() {
            @Override
            public void onDataChannel(int id, String label) {
                // The offerer is the channel creator, so this is not invoked on its side.
            }
        };

        PeerConnection.Observer answererObs = new PeerConnection.Observer() {
            @Override
            public void onDataChannel(int id, String label) {
                pair[0].answerer.setDataChannelCallbacks(id,
                        (cid, data) -> {
                            System.out.println("[answerer] received: " + new String(data));
                            received.countDown();
                        },
                        cid -> {
                            System.out.println("[answerer] data channel open " + cid);
                            Thread.ofVirtual().start(() ->
                                    pair[0].answerer.sendDataChannelText(cid, "Hello from Java answerer!"));
                        },
                        cid -> System.out.println("[answerer] data channel closed " + cid));
            }
        };

        pair[0] = PeerPair.prepare(offererObs, answererObs);

        int dcId = pair[0].offerer.createDataChannel("data", true);
        pair[0].offerer.setDataChannelCallbacks(dcId,
                (cid, data) -> {
                    System.out.println("[offerer] received: " + new String(data));
                    received.countDown();
                },
                cid -> {
                    System.out.println("[offerer] data channel open " + cid);
                    Thread.ofVirtual().start(() ->
                            pair[0].offerer.sendDataChannelText(cid, "Hello from Java offerer!"));
                },
                cid -> System.out.println("[offerer] data channel closed " + cid));

        pair[0].negotiate();
        System.out.println("Connected. Exchanging greetings...");
        received.await(10, TimeUnit.SECONDS);

        pair[0].close();
        System.out.println("Done.");
    }
}
