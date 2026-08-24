package io.github.kinsleykajiva.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kinsleykajiva.server.protocol.ErrorDetail;
import io.github.kinsleykajiva.server.protocol.Signal;
import io.github.kinsleykajiva.server.protocol.SignalAction;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

/**
 * Async WebSocket signaling server. Each connection owns one {@link EchoSession}.
 * Inbound messages are dispatched onto virtual threads so the NIO read loop is
 * never blocked by (potentially slow) PeerConnection SDP/ICE work.
 */
public final class SignalingServer extends WebSocketServer {

    private static final Logger LOG = Logger.getLogger(SignalingServer.class.getName());
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentMap<WebSocket, EchoSession> sessions = new ConcurrentHashMap<>();

    public SignalingServer(InetSocketAddress address) {
        super(address);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        EchoSession session = new EchoSession(sig -> sendSignal(conn, sig));
        sessions.put(conn, session);
        sendSignal(conn, Signal.event(SignalAction.READY, session.sessionId()));
        LOG.info("client connected session=" + session.sessionId());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        EchoSession session = sessions.remove(conn);
        if (session != null) {
            session.close();
            LOG.info("client disconnected session=" + session.sessionId());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.warning("ws error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        LOG.info("signaling server listening on " + getAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        EchoSession session = sessions.get(conn);
        if (session == null) return;
        if (message == null || message.isBlank()) return;
        final WebSocket c = conn;
        Thread.startVirtualThread(() -> {
            try {
                Signal req = mapper.readValue(message, Signal.class);
                session.handle(req);
            } catch (Exception e) {
                LOG.warning("bad message from session=" + session.sessionId() + ": " + e.getMessage());
                sendSignal(c, Signal.event(SignalAction.ERROR, session.sessionId())
                        .withError(ErrorDetail.CODE_BAD_REQUEST, "malformed message: " + e.getMessage()));
            }
        });
    }

    private void sendSignal(WebSocket conn, Signal signal) {
        try {
            conn.send(mapper.writeValueAsString(signal));
        } catch (Exception e) {
            LOG.warning("send failed: " + e.getMessage());
        }
    }
}
