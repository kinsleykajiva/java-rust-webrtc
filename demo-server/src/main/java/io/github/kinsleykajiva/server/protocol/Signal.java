package io.github.kinsleykajiva.server.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Common, versioned envelope used for every message exchanged between the echo
 * client and the signaling server.
 *
 * <pre>
 * {
 *   "version": "1.0",
 *   "type": "request" | "response" | "event",
 *   "action": "join" | "offer" | "answer" | "candidate" | "media-state" | "leave" | "ready" | "ack" | "bye" | "error",
 *   "session": "&lt;server-assigned session id&gt;",
 *   "transaction": "&lt;correlation id (echoed back for request/response pairs)&gt;",
 *   "payload": { ... action-specific fields ... },
 *   "error": { "code": 0, "message": "..." }
 * }
 * </pre>
 *
 * <p>Design goals: a single shape for every message, request/response correlation
 * via {@code transaction}, and a uniform {@link ErrorDetail} block so callers can
 * branch on {@code error != null} instead of scattering status codes.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "version", "type", "action", "session", "transaction", "payload", "error" })
public final class Signal {

    public static final String VERSION = "1.0";

    public String version = VERSION;
    public String type;
    public String action;
    public String session;
    public String transaction;
    public Map<String, Object> payload = new LinkedHashMap<>();
    public ErrorDetail error;

    public Signal() {
    }

    private Signal(String type, String action, String session, String transaction) {
        this.type = type;
        this.action = action;
        this.session = session;
        this.transaction = transaction;
    }

    /** Outbound request originating from either side. */
    public static Signal request(String action, String session, String transaction) {
        return new Signal("request", action, session, transaction);
    }

    /** Outbound response correlated to a received request (same transaction). */
    public static Signal response(String action, String session, String transaction) {
        return new Signal("response", action, session, transaction);
    }

    /** Unsolicited server-to-client notification. */
    public static Signal event(String action, String session) {
        return new Signal("event", action, session, null);
    }

    public Signal withPayload(String key, Object value) {
        this.payload.put(key, value);
        return this;
    }

    public Signal withError(int code, String message) {
        this.error = new ErrorDetail(code, message);
        return this;
    }

    public String txn() {
        return transaction != null ? transaction : UUID.randomUUID().toString();
    }

    public String str(String key) {
        Object v = payload.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public Boolean bool(String key) {
        Object v = payload.get(key);
        return v instanceof Boolean b ? b : null;
    }

    public Integer integer(String key) {
        Object v = payload.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
