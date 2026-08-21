package io.github.kinsleykajiva;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Java port of the webrtc-rs {@code av1::depacketizer::Av1Depacketizer}.
 *
 * <p>Converts AV1 RTP payloads (which carry an RTP aggregation header and may be
 * fragmented across packets) into the AV1 low-overhead bitstream format (OBU stream
 * with {@code obu_size} fields) that an IVF container expects. Each RTP payload is
 * passed to {@link #depacketize(byte[])}; the returned bytes are written as one IVF
 * frame. Used by {@link SaveToDiskAv1Demo}.</p>
 *
 * <p>Reference: https://aomediacodec.github.io/av1-rtp-spec/</p>
 */
public final class Av1Depacketizer {

    // Aggregation header bit masks (|Z|Y|W|N|-|-|-|)
    private static final int AV1_Z_MASK = 0b1000_0000;
    private static final int AV1_Y_MASK = 0b0100_0000;
    private static final int AV1_W_MASK = 0b0011_0000;
    private static final int AV1_N_MASK = 0b0000_1000;

    // OBU header bits
    private static final int OBU_HAS_SIZE_BIT = 0b0000_0010;
    private static final int OBU_HAS_EXTENSION_BIT = 0b0000_0100;
    private static final int OBU_TYPE_MASK = 0b0111_1000;
    private static final int OBU_TYPE_TEMPORAL_DELIMITER = 2;
    private static final int OBU_TYPE_TILE_LIST = 8;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean z;
    private boolean y;
    private boolean n;

    public byte[] depacketize(byte[] payload) throws IOException {
        if (payload.length <= 1) {
            throw new IOException("short AV1 packet");
        }
        boolean obuZ = (payload[0] & AV1_Z_MASK) != 0;
        boolean obuY = (payload[0] & AV1_Y_MASK) != 0;
        int obuCount = (payload[0] & AV1_W_MASK) >> 4;
        boolean obuN = (payload[0] & AV1_N_MASK) != 0;
        this.z = obuZ;
        this.y = obuY;
        this.n = obuN;

        if (obuN) {
            buffer.reset();
        }
        if (!obuZ && buffer.size() != 0) {
            buffer.reset();
        }

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int offset = 1; // skip aggregation header
        int obuOffset = 0;

        while (offset < payload.length) {
            boolean isFirst = obuOffset == 0;
            boolean isLast = obuCount != 0 && obuOffset == (obuCount - 1);

            int lengthField;
            boolean isLast2;
            if (obuCount == 0 || !isLast) {
                long[] leb = readLeb128(payload, offset);
                long len = leb[0];
                int n = (int) leb[1];
                if (n == 0) {
                    throw new IOException("short AV1 packet (leb128)");
                }
                offset += n;
                boolean isLastW0 = obuCount == 0 && offset + (int) len == payload.length;
                lengthField = (int) len;
                isLast2 = isLast || isLastW0;
            } else {
                lengthField = payload.length - offset;
                isLast2 = true;
            }

            if (offset + lengthField > payload.length) {
                throw new IOException("short AV1 packet (length field)");
            }

            byte[] obuBuffer;
            if (isFirst && obuZ) {
                if (buffer.size() == 0) {
                    if (isLast2) break;
                    offset += lengthField;
                    obuOffset++;
                    continue;
                }
                byte[] prev = buffer.toByteArray();
                obuBuffer = new byte[prev.length + lengthField];
                System.arraycopy(prev, 0, obuBuffer, 0, prev.length);
                System.arraycopy(payload, offset, obuBuffer, prev.length, lengthField);
                buffer.reset();
            } else {
                obuBuffer = new byte[lengthField];
                System.arraycopy(payload, offset, obuBuffer, 0, lengthField);
            }
            offset += lengthField;

            if (isLast2 && obuY) {
                buffer.reset();
                buffer.write(obuBuffer);
                break;
            }

            if (obuBuffer.length == 0) {
                if (isLast2) break;
                obuOffset++;
                continue;
            }

            int obuType = (obuBuffer[0] & OBU_TYPE_MASK) >> 3;
            if (obuType == OBU_TYPE_TEMPORAL_DELIMITER || obuType == OBU_TYPE_TILE_LIST) {
                if (isLast2) break;
                obuOffset++;
                continue;
            }

            boolean hasSizeField = (obuBuffer[0] & OBU_HAS_SIZE_BIT) != 0;
            boolean hasExtension = (obuBuffer[0] & OBU_HAS_EXTENSION_BIT) != 0;
            int headerSize = hasExtension ? 2 : 1;

            if (hasSizeField) {
                long[] leb = readLeb128(obuBuffer, headerSize);
                long obuSize = leb[0];
                int lebSize = (int) leb[1];
                if (lebSize == 0) {
                    throw new IOException("short AV1 OBU size");
                }
                long expectedSize = headerSize + lebSize + obuSize;
                int actualSize = (isFirst && obuZ) ? obuBuffer.length : lengthField;
                if (actualSize != expectedSize) {
                    throw new IOException("AV1 OBU size mismatch");
                }
                result.write(obuBuffer);
            } else {
                result.write(obuBuffer[0] | OBU_HAS_SIZE_BIT);
                if (hasExtension && obuBuffer.length > 1) {
                    result.write(obuBuffer[1]);
                }
                int payloadSize = obuBuffer.length - headerSize;
                writeLeb128(result, payloadSize);
                if (headerSize < obuBuffer.length) {
                    result.write(obuBuffer, headerSize, obuBuffer.length - headerSize);
                }
            }

            if (isLast2) break;
            obuOffset++;
        }

        if (obuCount != 0 && obuOffset != (obuCount - 1) && !this.y) {
            throw new IOException("AV1 OBU count mismatch");
        }

        return result.toByteArray();
    }

    /** Reads an unsigned LEB128 value starting at {@code off}; returns {value, bytesRead}. */
    private static long[] readLeb128(byte[] data, int off) {
        long result = 0;
        int shift = 0;
        int i = off;
        while (i < data.length) {
            int b = data[i] & 0xFF;
            result |= (long) (b & 0x7F) << shift;
            i++;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return new long[]{result, (long) (i - off)};
    }

    private static void writeLeb128(ByteArrayOutputStream out, int value) {
        int v = value;
        while (true) {
            int b = v & 0x7F;
            v >>>= 7;
            if (v != 0) b |= 0x80;
            out.write(b);
            if (v == 0) break;
        }
    }
}
