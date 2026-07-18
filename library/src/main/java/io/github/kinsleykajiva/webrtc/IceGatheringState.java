package io.github.kinsleykajiva.webrtc;

/** ICE gathering state, mirroring {@code RTCIceGatheringState}. */
public enum IceGatheringState {
    NEW(0),
    GATHERING(1),
    COMPLETE(2);

    public final int value;

    IceGatheringState(int value) {
        this.value = value;
    }

    public static IceGatheringState fromValue(int value) {
        for (IceGatheringState s : values()) {
            if (s.value == value) {
                return s;
            }
        }
        return NEW;
    }
}
