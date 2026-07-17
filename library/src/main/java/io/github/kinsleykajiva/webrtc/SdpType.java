package io.github.kinsleykajiva.webrtc;

/** SDP session description types, mirroring {@code RTCSdpType}. */
public enum SdpType {
    UNSPECIFIED(0),
    OFFER(1),
    ANSWER(2),
    PRANSWER(3),
    ROLLBACK(4);

    public final int value;

    SdpType(int value) {
        this.value = value;
    }

    public static SdpType fromValue(int value) {
        for (SdpType t : values()) {
            if (t.value == value) {
                return t;
            }
        }
        return UNSPECIFIED;
    }
}
