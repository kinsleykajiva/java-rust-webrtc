package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Mirrors the Rust {@code data-channels-create} example: the offerer explicitly creates
 * an ordered data channel, creates an offer, and once the channel opens it sends a
 * random message every 5 seconds. The answerer prints whatever it receives.
 */
public class DataChannelsCreateDemo {

    private static final String CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static String randomMessage() {
        StringBuilder sb = new StringBuilder(15);
        for (int i = 0; i < 15; i++) {
            sb.append(CHARS.charAt((int) (Math.random() * CHARS.length())));
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        CountDownLatch received = new CountDownLatch(3);

        final PeerPair[] pair = new PeerPair[1];

        PeerConnection.Observer offererObs = new PeerConnection.Observer() {
            @Override
            public void onDataChannel(int id, String label) {
                // offerer is the creator
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
                        cid -> System.out.println("[answerer] data channel open " + cid),
                        cid -> System.out.println("[answerer] data channel closed " + cid));
            }
        };

        pair[0] = PeerPair.prepare(offererObs, answererObs);

        int dcId = pair[0].offerer.createDataChannel("data", true);
        pair[0].offerer.setDataChannelCallbacks(dcId,
                (cid, data) -> System.out.println("[offerer] received: " + new String(data)),
                cid -> {
                    System.out.println("[offerer] data channel open " + cid);
                    Thread.ofVirtual().start(() -> {
                        try {
                            for (int i = 0; i < 3; i++) {
                                String msg = randomMessage();
                                pair[0].offerer.sendDataChannelText(cid, msg);
                                System.out.println("[offerer] sent: " + msg);
                                Thread.sleep(5000);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                },
                cid -> System.out.println("[offerer] data channel closed " + cid));

        pair[0].negotiate();
        System.out.println("Connected. Offerer will send 3 random messages...");
        received.await(20, TimeUnit.SECONDS);

        pair[0].close();
        System.out.println("Done.");
    }
}
