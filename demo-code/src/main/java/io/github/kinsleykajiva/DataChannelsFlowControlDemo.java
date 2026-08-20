package io.github.kinsleykajiva;

import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mirrors the Rust {@code data-channels-flow-control} example: a requester sends data as
 * fast as possible over an unordered data channel while a responder measures throughput.
 *
 * <p>The Rust example pauses/resumes sending using SCTP buffered-amount high/low
 * thresholds. The current Java FFI does not expose {@code set_buffered_amount_low_threshold}
 * / the buffered-amount callbacks, so this demo instead sends as fast as the SCTP layer
 * will accept (backing off briefly when a send fails because the buffer is full) and lets
 * the responder measure the achieved throughput.</p>
 */
public class DataChannelsFlowControlDemo {

    private static final int CHUNK = 32 * 1024;
    private static final long STOP_BYTES = 50L * 1024 * 1024; // 50 MB

    public static void main(String[] args) throws Exception {
        WebRtc.initialize();

        final byte[] payload = new byte[CHUNK];
        for (int i = 0; i < CHUNK; i++) payload[i] = (byte) (i & 0xff);

        CountDownLatch done = new CountDownLatch(1);
        AtomicLong received = new AtomicLong(0);
        AtomicBoolean stop = new AtomicBoolean(false);

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
                            long total = received.addAndGet(data.length);
                            if (total >= STOP_BYTES) {
                                stop.set(true);
                                try {
                                    pair[0].answerer.sendDataChannelText(cid, "done");
                                } catch (Exception ignore) {
                                }
                                done.countDown();
                            }
                        },
                        cid -> System.out.println("[responder] data channel open " + cid),
                        cid -> {
                            System.out.println("[responder] data channel closed " + cid);
                            done.countDown();
                        });
            }
        };

        pair[0] = PeerPair.prepare(offererObs, answererObs);

        int dcId = pair[0].offerer.createDataChannel("data", false); // unordered -> max throughput
        pair[0].offerer.setDataChannelCallbacks(dcId,
                (cid, data) -> {
                    if (new String(data).equals("done")) {
                        stop.set(true);
                    }
                },
                cid -> {
                    System.out.println("[requester] data channel open " + cid);
                    Thread.ofVirtual().start(() -> {
                        long sent = 0;
                        long start = System.nanoTime();
                        long lastReport = start;
                        long lastSent = 0;
                        try {
                            while (!stop.get() && sent < STOP_BYTES * 4) {
                                try {
                                    pair[0].offerer.sendDataChannelBytes(cid, payload);
                                    sent += CHUNK;
                                } catch (Exception e) {
                                    // SCTP send buffer is full; back off briefly.
                                    Thread.sleep(1);
                                }
                                long now = System.nanoTime();
                                if (now - lastReport >= 1_000_000_000L) {
                                    double interval = (now - lastReport) / 1e9;
                                    double mbps = (sent - lastSent) * 8.0 / 1e6 / interval;
                                    System.out.printf("[requester] ~%.1f Mbps (sent %.2f MB)%n", mbps, sent / 1e6);
                                    lastReport = now;
                                    lastSent = sent;
                                }
                            }
                            System.out.printf("[requester] total sent %.2f MB in %.1f s%n",
                                    sent / 1e6, (System.nanoTime() - start) / 1e9);
                            try {
                                pair[0].offerer.close();
                            } catch (Exception ignore) {
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                },
                cid -> {
                    System.out.println("[requester] data channel closed " + cid);
                    done.countDown();
                });

        pair[0].negotiate();
        System.out.println("Connected. Requester sends 32 KB chunks as fast as possible; responder measures throughput...");
        done.await(60, TimeUnit.SECONDS);
        pair[0].close();
        System.out.println("Done.");
    }
}
