package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Mirrors the Rust {@code data-channels-close} example: after exchanging a few messages
 * the data channel is closed and both peers observe the close event via their
 * {@code onClose} callback.
 *
 * <p>Note: the current FFI does not expose a per-data-channel {@code close()} (only
 * closing the whole peer connection). This demo therefore closes the offerer's peer
 * connection, which tears down the SCTP association and triggers {@code onClose} on both
 * sides.</p>
 */
public class DataChannelsCloseDemo {

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        int messagesToSend = 5;
        CountDownLatch closed = new CountDownLatch(1);

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
                        (cid, data) -> System.out.println("[answerer] received: " + new String(data)),
                        cid -> System.out.println("[answerer] data channel open " + cid),
                        cid -> {
                            System.out.println("[answerer] data channel closed " + cid);
                            closed.countDown();
                        });
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
                            for (int i = 0; i < messagesToSend; i++) {
                                String msg = "message " + (i + 1);
                                pair[0].offerer.sendDataChannelText(cid, msg);
                                System.out.println("[offerer] sent: " + msg);
                                Thread.sleep(5000);
                            }
                            System.out.println("[offerer] closing peer connection (tears down data channel)");
                            pair[0].offerer.close();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                },
                cid -> {
                    System.out.println("[offerer] data channel closed " + cid);
                    closed.countDown();
                });

        pair[0].negotiate();
        System.out.println("Connected. Offerer will send " + messagesToSend + " messages then close...");
        closed.await(40, TimeUnit.SECONDS);

        pair[0].close();
        System.out.println("Done.");
    }
}
