package io.github.kinsleykajiva;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal IVF (Indeo Video Format) writer. Each written "frame" is stored verbatim
 * as an IVF frame entry (4-byte size + 8-byte timestamp + payload), matching how the
 * Rust {@code save-to-disk-av1} example saves media via {@code IVFWriter::write_rtp}
 * (it stores each RTP payload as an IVF frame). Used by {@link SaveToDiskAv1Demo}.
 */
public final class IvfWriter implements AutoCloseable {

    private final DataOutputStream out;
    private int frameCount;

    public IvfWriter(OutputStream os, int width, int height, int timebaseNumerator, int timebaseDenominator)
            throws IOException {
        this.out = new DataOutputStream(new BufferedOutputStream(os));
        ByteBuffer h = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        h.put("DKIF".getBytes());          // signature
        h.putShort((short) 0);             // version
        h.putShort((short) 32);            // header size
        h.put("AV01".getBytes());          // four_cc
        h.putShort((short) width);
        h.putShort((short) height);
        h.putInt(timebaseDenominator);
        h.putInt(timebaseNumerator);
        h.putInt(0);                       // num_frames (filled at close if possible)
        h.putInt(0);                       // unused
        out.write(h.array());
        out.flush();
    }

    public synchronized void writeFrame(byte[] payload, long timestamp) throws IOException {
        ByteBuffer entry = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        entry.putInt(payload.length);
        entry.putLong(timestamp);
        out.write(entry.array());
        out.write(payload);
        frameCount++;
    }

    public int frameCount() {
        return frameCount;
    }

    @Override
    public void close() throws IOException {
        out.flush();
        out.close();
    }
}
