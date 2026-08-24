package io.github.kinsleykajiva.server.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Action verbs shared by client and server. Keeping them in one place guarantees
 * the client and server agree on the signaling vocabulary.
 */
public final class SignalAction {

    // Lifecycle
    public static final String JOIN = "join";
    public static final String READY = "ready";
    public static final String LEAVE = "leave";
    public static final String BYE = "bye";

    // SDP exchange
    public static final String OFFER = "offer";
    public static final String ANSWER = "answer";

    // ICE
    public static final String CANDIDATE = "candidate";

    // Media control (client notifies the server; echo simply relays what it gets)
    public static final String MEDIA_STATE = "media-state";

    // Generic acks / errors
    public static final String ACK = "ack";
    public static final String ERROR = "error";

    // Server events
    public static final String PEER_CONNECTED = "peer-connected";
    public static final String PEER_DISCONNECTED = "peer-disconnected";

    private SignalAction() {
    }
}
