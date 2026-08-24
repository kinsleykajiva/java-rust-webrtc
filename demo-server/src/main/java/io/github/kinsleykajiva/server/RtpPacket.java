package io.github.kinsleykajiva.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Builds complete RTP packets (12-byte header + payload) for the echo track.
 * The library rewrites the SSRC to the track's configured value, but the payload
 * type must match what the browser negotiated for the loopback stream.
 */
public final class RtpPacket {

    private RtpPacket() {
    }

    public static byte[] build(int ssrc, int payloadType, int sequenceNumber, int timestamp, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(12 + payload.length).order(ByteOrder.BIG_ENDIAN);
        // V=2, P=0, X=0, CC=0, M=0, PT=payloadType
        buf.put((byte) 0x80);
        buf.put((byte) (payloadType & 0x7F));
        buf.putShort((short) (sequenceNumber & 0xFFFF));
        buf.putInt(timestamp);
        buf.putInt(ssrc);
        buf.put(payload);
        return buf.array();
    }
}
