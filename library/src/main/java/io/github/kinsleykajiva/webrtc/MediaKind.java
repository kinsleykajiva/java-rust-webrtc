package io.github.kinsleykajiva.webrtc;

/** Media kinds for transceivers, mirroring {@code RtpCodecKind}. */
public enum MediaKind {
    UNSPECIFIED(0),
    AUDIO(1),
    VIDEO(2);

    public final int value;

    MediaKind(int value) {
        this.value = value;
    }

    public static MediaKind fromValue(int value) {
        for (MediaKind k : values()) {
            if (k.value == value) {
                return k;
            }
        }
        return UNSPECIFIED;
    }
}
