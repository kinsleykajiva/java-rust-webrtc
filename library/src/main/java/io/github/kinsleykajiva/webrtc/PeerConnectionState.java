package io.github.kinsleykajiva.webrtc;

/** Peer connection states, mirroring {@code RTCPeerConnectionState}. */
public enum PeerConnectionState {
    UNSPECIFIED(0),
    NEW(1),
    CONNECTING(2),
    CONNECTED(3),
    DISCONNECTED(4),
    FAILED(5),
    CLOSED(6);

    public final int value;

    PeerConnectionState(int value) {
        this.value = value;
    }

    public static PeerConnectionState fromValue(int value) {
        for (PeerConnectionState s : values()) {
            if (s.value == value) {
                return s;
            }
        }
        return UNSPECIFIED;
    }
}
