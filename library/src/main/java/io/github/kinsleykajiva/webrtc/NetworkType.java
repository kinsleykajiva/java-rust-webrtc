package io.github.kinsleykajiva.webrtc;

/**
 * ICE network types, mirroring {@code NetworkType}. Used as a bitmask when configuring
 * a {@link Configuration} via {@link Configuration#setTransport}.
 */
public enum NetworkType {
    UDP(1),
    TCP(2);

    public final int value;

    NetworkType(int value) {
        this.value = value;
    }
}
