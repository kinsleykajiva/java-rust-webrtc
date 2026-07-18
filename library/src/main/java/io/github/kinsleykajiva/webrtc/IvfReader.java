package io.github.kinsleykajiva.webrtc;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads IVF (Indeo Video Format) containers and returns individual video frames.
 *
 * <p>IVF is the standard container for VP8, VP9, and AV1 raw frame data used in WebRTC.
 * The file starts with a 32-byte header describing width, height, frame rate, and frame count.
 * Each frame is preceded by a 12-byte entry (4-byte size + 8-byte timestamp).</p>
 *
 * <h3>Content</h3>
 * <ul>
 *   <li>VP8: {@link MimeTypes#CONTENT_VP8} ({@code demo-content/output_vp8.ivf})</li>
 *   <li>VP9: {@link MimeTypes#CONTENT_VP9} ({@code demo-content/output_vp9.ivf})</li>
 *   <li>AV1: generate with {@code ffmpeg -i input.mp4 -c:v libaom-av1 -crf 30 output.ivf}</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * try (var reader = new IvfReader(new FileInputStream(MimeTypes.CONTENT_VP8))) {
 *     IvfReader.IvfFrame frame;
 *     while ((frame = reader.nextFrame()) != null) {
 *         track.writeSample(payloadType, frame.data(), 33);
 *     }
 * }
 * }</pre>
 */
public final class IvfReader implements AutoCloseable {

    private static final int FILE_HEADER_SIZE = 32;

    public record IvfFileHeader(int width, int height, int timebaseDenominator, int timebaseNumerator, int numFrames) {}

    public record IvfFrame(byte[] data, long timestamp) {}

    private final BufferedInputStream input;
    private final IvfFileHeader fileHeader;

    public IvfReader(InputStream input) throws IOException {
        this.input = new BufferedInputStream(input);

        byte[] sig = readBytes(4);
        if (sig[0] != 'D' || sig[1] != 'K' || sig[2] != 'I' || sig[3] != 'F') {
            throw new IOException("IVF signature mismatch");
        }

        int version = readU16Le();
        int headerSize = readU16Le();

        if (version != 0) {
            throw new IOException("Unknown IVF version: " + version);
        }

        readBytes(4);
        int width = readU16Le();
        int height = readU16Le();
        int timebaseDenominator = (int) readU32Le();
        int timebaseNumerator = (int) readU32Le();
        int numFrames = (int) readU32Le();
        readU32Le();

        if (headerSize > FILE_HEADER_SIZE) {
            long remaining = headerSize - FILE_HEADER_SIZE;
            while (remaining > 0) {
                long skipped = this.input.skip(remaining);
                if (skipped <= 0) {
                    if (this.input.read() < 0) {
                        throw new EOFException("Unexpected end of IVF header");
                    }
                    remaining--;
                } else {
                    remaining -= skipped;
                }
            }
        }

        this.fileHeader = new IvfFileHeader(width, height, timebaseDenominator, timebaseNumerator, numFrames);
    }

    public IvfFileHeader fileHeader() {
        return fileHeader;
    }

    public IvfFrame nextFrame() throws IOException {
        int frameSize = (int) readU32Le();
        long timestamp = readU64Le();
        byte[] data = readBytes(frameSize);
        return new IvfFrame(data, timestamp);
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    private int readU16Le() throws IOException {
        int b0 = readUnsignedByte();
        int b1 = readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private long readU32Le() throws IOException {
        long b0 = readUnsignedByte();
        long b1 = readUnsignedByte();
        long b2 = readUnsignedByte();
        long b3 = readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private long readU64Le() throws IOException {
        long b0 = readUnsignedByte();
        long b1 = readUnsignedByte();
        long b2 = readUnsignedByte();
        long b3 = readUnsignedByte();
        long b4 = readUnsignedByte();
        long b5 = readUnsignedByte();
        long b6 = readUnsignedByte();
        long b7 = readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)
                | (b4 << 32) | (b5 << 40) | (b6 << 48) | (b7 << 56);
    }

    private int readUnsignedByte() throws IOException {
        int b = input.read();
        if (b < 0) {
            throw new EOFException("Unexpected end of stream");
        }
        return b;
    }

    private byte[] readBytes(int n) throws IOException {
        byte[] buf = new byte[n];
        int offset = 0;
        while (offset < n) {
            int read = input.read(buf, offset, n - offset);
            if (read < 0) {
                throw new EOFException("Unexpected end of stream");
            }
            offset += read;
        }
        return buf;
    }
}
