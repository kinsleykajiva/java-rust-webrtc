package io.github.kinsleykajiva.server;

import com.sun.net.httpserver.HttpServer;
import io.github.kinsleykajiva.webrtc.WebRtc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Entry point for the echo / SFU demo server.
 *
 * <ul>
 *   <li>An HTTP server serves {@code echo-test.html} (open it in a browser).</li>
 *   <li>A WebSocket server handles signaling (offer/answer/ICE, JSON envelope).</li>
 * </ul>
 *
 * <p>Usage: {@code java -jar JavaRust-Webrtc-demo-server.jar [--http 8080] [--ws 8081]}</p>
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private static final int DEFAULT_HTTP = 8080;
    private static final int DEFAULT_WS = 8081;

    public static void main(String[] args) throws Exception {
        int httpPort = DEFAULT_HTTP;
        int wsPort = DEFAULT_WS;
        for (int i = 0; i < args.length; i++) {
            if ("--http".equals(args[i]) && i + 1 < args.length) {
                httpPort = Integer.parseInt(args[++i]);
            } else if ("--ws".equals(args[i]) && i + 1 < args.length) {
                wsPort = Integer.parseInt(args[++i]);
            }
        }

        // Eagerly load the native Rust WebRTC engine once at startup.
        try {
            WebRtc.initialize();
            LOG.info("native webrtc engine: " + WebRtc.probe());
        } catch (Throwable t) {
            LOG.severe("failed to load native webrtc engine: " + t.getMessage());
            LOG.severe("set -Dwebrtc.native.lib=<absolute-path> if the bundled library is missing.");
            System.exit(1);
        }

        startHttp(httpPort);
        startWs(wsPort);

        LOG.info("Echo demo ready. Open http://localhost:" + httpPort + "/echo-test.html");
        LOG.info("Press Ctrl+C to stop.");

        // Keep the main thread alive until interrupted.
        Thread.currentThread().join();
    }

    private static void startHttp(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                byte[] body = readResource("/echo-test.html");
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        LOG.info("http server listening on " + port);
    }

    private static void startWs(int port) {
        SignalingServer ws = new SignalingServer(new InetSocketAddress(port));
        ws.start();
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream in = Main.class.getResourceAsStream(path)) {
            if (in == null) {
                return ("<!-- missing resource: " + path + " -->").getBytes(StandardCharsets.UTF_8);
            }
            return in.readAllBytes();
        }
    }
}
