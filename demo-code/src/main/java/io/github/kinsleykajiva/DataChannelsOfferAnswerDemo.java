package io.github.kinsleykajiva;

import com.sun.net.httpserver.HttpServer;
import io.github.kinsleykajiva.webrtc.Configuration;
import io.github.kinsleykajiva.webrtc.DtlsRole;
import io.github.kinsleykajiva.webrtc.NetworkType;
import io.github.kinsleykajiva.webrtc.PeerConnection;
import io.github.kinsleykajiva.webrtc.SdpType;
import io.github.kinsleykajiva.webrtc.SessionDescription;
import io.github.kinsleykajiva.webrtc.WebRtc;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Mirrors the Rust {@code data-channels-offer-answer} example, which uses two separate
 * processes that exchange their SDP offer/answer (and ICE candidates) over HTTP.
 *
 * <p>Run two JVMs:</p>
 * <pre>
 *   java --enable-native-access=ALL-UNNAMED -cp ... io.github.kinsleykajiva.DataChannelsOfferAnswerDemo answer
 *   java --enable-native-access=ALL-UNNAMED -cp ... io.github.kinsleykajiva.DataChannelsOfferAnswerDemo offer
 * </pre>
 *
 * <p>The answer process listens on port 8082 and the offer process on 8081. SDP and ICE
 * candidates are POSTed between them. Once connected, both sides send a numbered message
 * ("offer-N" / "answer-N") every 5 seconds.</p>
 */
public class DataChannelsOfferAnswerDemo {

    private static final int ANSWER_PORT = 8082;
    private static final int OFFER_PORT = 8081;
    private static final int RUN_SECONDS = 25;

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || (!args[0].equals("offer") && !args[0].equals("answer"))) {
            System.out.println("Usage: DataChannelsOfferAnswerDemo <offer|answer>");
            System.exit(1);
        }
        WebRtc.initialize();
        if (args[0].equals("offer")) {
            runOffer();
        } else {
            runAnswer();
        }
    }

    /** Buffers ICE candidates until the remote description has been applied. */
    private static final class IceBuffer {
        private final PeerConnection pc;
        private volatile boolean remoteSet = false;
        private final List<String> pending = new ArrayList<>();

        IceBuffer(PeerConnection pc) {
            this.pc = pc;
        }

        synchronized void setRemote() {
            remoteSet = true;
            for (String c : pending) {
                pc.addIceCandidate(c, "", 0);
            }
            pending.clear();
        }

        synchronized void add(String candidate) {
            if (remoteSet) {
                pc.addIceCandidate(candidate, "", 0);
            } else {
                pending.add(candidate);
            }
        }
    }

    private static void post(String url, String body) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            client.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.err.println("[signaling] POST " + url + " failed: " + e.getMessage());
        }
    }

    private static void runOffer() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        CompletableFuture<String> answerFuture = new CompletableFuture<>();

        final PeerConnection[] pc = new PeerConnection[1];

        PeerConnection.Observer observer = new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                post("http://localhost:" + ANSWER_PORT + "/ice", candidate);
            }

            @Override
            public void onDataChannel(int id, String label) {
                // offerer is the creator
            }
        };

        Configuration offererCfg = Configuration.create()
                .setTransport("", "127.0.0.1:0", 0, NetworkType.TCP.value);
        pc[0] = PeerConnection.create(offererCfg, observer);
        IceBuffer ice = new IceBuffer(pc[0]);

        HttpServer server = HttpServer.create(new InetSocketAddress(OFFER_PORT), 0);
        server.createContext("/answer", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            answerFuture.complete(body);
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.createContext("/ice", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ice.add(body);
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();

        int dcId = pc[0].createDataChannel("data", true);
        pc[0].setDataChannelCallbacks(dcId,
                (cid, data) -> System.out.println("[offer] received: " + new String(data)),
                cid -> {
                    System.out.println("[offer] data channel open " + cid);
                    Thread.ofVirtual().start(() -> {
                        try {
                            for (int i = 1; i <= RUN_SECONDS / 5 + 2; i++) {
                                if (Thread.currentThread().isInterrupted()) return;
                                pc[0].sendDataChannelText(cid, "offer-" + i);
                                System.out.println("[offer] sent: offer-" + i);
                                Thread.sleep(5000);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                },
                cid -> {
                    System.out.println("[offer] data channel closed " + cid);
                    finished.countDown();
                });

        SessionDescription offer = pc[0].createOffer();
        String offerSdp = offer.getSdp();
        pc[0].setLocalDescription(offer);
        post("http://localhost:" + ANSWER_PORT + "/offer", offerSdp);

        String answerSdp = answerFuture.get(20, TimeUnit.SECONDS);
        SessionDescription answer = SessionDescription.create(SdpType.ANSWER, answerSdp);
        pc[0].setRemoteDescription(answer);
        ice.setRemote();

        System.out.println("[offer] connected; running for ~" + RUN_SECONDS + "s");
        finished.await(RUN_SECONDS + 10, TimeUnit.SECONDS);
        pc[0].close();
        server.stop(0);
        System.out.println("Done.");
    }

    private static void runAnswer() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);

        final PeerConnection[] pc = new PeerConnection[1];

        PeerConnection.Observer observer = new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(String candidate, String sdpMid) {
                post("http://localhost:" + OFFER_PORT + "/ice", candidate);
            }

            @Override
            public void onDataChannel(int id, String label) {
                System.out.println("[answer] incoming data channel " + id + " label=" + label);
                pc[0].setDataChannelCallbacks(id,
                        (cid, data) -> System.out.println("[answer] received: " + new String(data)),
                        cid -> {
                            System.out.println("[answer] data channel open " + cid);
                            Thread.ofVirtual().start(() -> {
                                try {
                                    for (int i = 1; i <= RUN_SECONDS / 5 + 2; i++) {
                                        if (Thread.currentThread().isInterrupted()) return;
                                        pc[0].sendDataChannelText(cid, "answer-" + i);
                                        System.out.println("[answer] sent: answer-" + i);
                                        Thread.sleep(5000);
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
                        },
                        cid -> {
                            System.out.println("[answer] data channel closed " + cid);
                            finished.countDown();
                        });
            }
        };

        Configuration answererCfg = Configuration.create()
                .useTcpOnly("127.0.0.1:8443", DtlsRole.CLIENT);
        pc[0] = PeerConnection.create(answererCfg, observer);
        IceBuffer ice = new IceBuffer(pc[0]);

        HttpServer server = HttpServer.create(new InetSocketAddress(ANSWER_PORT), 0);
        server.createContext("/offer", exchange -> {
            String offerSdp = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                SessionDescription offer = SessionDescription.create(SdpType.OFFER, offerSdp);
                pc[0].setRemoteDescription(offer);
                ice.setRemote();
                SessionDescription answer = pc[0].createAnswer();
                String answerSdp = answer.getSdp();
                pc[0].setLocalDescription(answer);
                byte[] body = answerSdp.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                post("http://localhost:" + OFFER_PORT + "/answer", answerSdp);
            } catch (Exception e) {
                byte[] body = ("error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        server.createContext("/ice", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ice.add(body);
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();

        System.out.println("[answer] listening on port " + ANSWER_PORT + "; waiting for offer...");
        finished.await(RUN_SECONDS + 10, TimeUnit.SECONDS);
        pc[0].close();
        server.stop(0);
        System.out.println("Done.");
    }
}
