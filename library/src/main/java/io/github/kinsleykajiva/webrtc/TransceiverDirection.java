package io.github.kinsleykajiva.webrtc;

/** Transceiver direction, mirroring {@code RTCRtpTransceiverDirection}. */
public enum TransceiverDirection {
    UNSPECIFIED(0),
    SEND_RECV(1),
    SEND_ONLY(2),
    RECV_ONLY(3),
    INACTIVE(4);

    public final int value;

    TransceiverDirection(int value) {
        this.value = value;
    }

    public static TransceiverDirection fromValue(int value) {
        for (TransceiverDirection d : values()) {
            if (d.value == value) {
                return d;
            }
        }
        return UNSPECIFIED;
    }
}
