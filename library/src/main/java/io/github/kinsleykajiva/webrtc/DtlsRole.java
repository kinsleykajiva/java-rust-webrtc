package io.github.kinsleykajiva.webrtc;

/** DTLS answering role, mirroring {@code RTCDtlsRole}. */
public enum DtlsRole {
    UNSPECIFIED(0),
    AUTO(1),
    CLIENT(2),
    SERVER(3);

    public final int value;

    DtlsRole(int value) {
        this.value = value;
    }

    public static DtlsRole fromValue(int value) {
        for (DtlsRole r : values()) {
            if (r.value == value) {
                return r;
            }
        }
        return UNSPECIFIED;
    }
}
