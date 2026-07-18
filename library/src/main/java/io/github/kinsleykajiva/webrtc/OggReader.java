package io.github.kinsleykajiva.webrtc;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads OGG containers with Opus audio and returns individual Opus frames.
 *
 * <p>Parses the {@code OpusHead} header to extract channel count, sample rate, and
 * pre-skip, then yields each subsequent OGG page payload as an {@link OggPage}.</p>
 *
 * <h3>Content</h3>
 * <ul>
 *   <li>{@link MimeTypes#CONTENT_OPUS} ({@code demo-content/output.ogg})</li>
 *   <li>{@link MimeTypes#CONTENT_PLAYLIST} ({@code demo-content/playlist.ogg})</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * try (var reader = new OggReader(new FileInputStream(MimeTypes.CONTENT_OPUS))) {
 *     OggReader.OggPage page;
 *     while ((page = reader.nextPage()) != null) {
 *         track.writeSample(payloadType, page.data(), 20);
 *     }
 * }
 * }</pre>
 */
public final class OggReader implements AutoCloseable {

    public record OggHeader(int channels, int sampleRate, int preSkip) {}

    public record OggPage(byte[] data, long granulePosition, int serial) {}

    private static final byte[] CAPTURE_PATTERN = {'O', 'g', 'g', 'S'};
    private static final byte[] OPUS_HEAD_MAGIC = {'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'};

    private final BufferedInputStream input;
    private OggHeader header;
    private boolean headerParsed;

    public OggReader(InputStream input) throws IOException {
        this.input = new BufferedInputStream(input, 65536);
        this.headerParsed = false;
        readOpusHeader();
    }

    public OggHeader header() {
        return header;
    }

    public OggPage nextPage() throws IOException {
        if (!headerParsed) {
            skipOpusTags();
        }

        byte[] capture = readBytes(4);
        if (capture[0] != CAPTURE_PATTERN[0] || capture[1] != CAPTURE_PATTERN[1] ||
            capture[2] != CAPTURE_PATTERN[2] || capture[3] != CAPTURE_PATTERN[3]) {
            throw new IOException("Invalid OGG capture pattern");
        }

        int version = readByte();
        if (version != 0) {
            throw new IOException("Unsupported OGG version: " + version);
        }

        readByte(); // header type flags
        long granulePosition = readU64();
        int serial = readU32();
        readU32(); // page sequence number
        readU32(); // CRC checksum

        int numSegments = readByte() & 0xFF;
        int[] segmentSizes = new int[numSegments];
        int totalPayloadSize = 0;
        for (int i = 0; i < numSegments; i++) {
            segmentSizes[i] = readByte() & 0xFF;
            totalPayloadSize += segmentSizes[i];
        }

        byte[] payload = readBytes(totalPayloadSize);
        return new OggPage(payload, granulePosition, serial);
    }

    private void readOpusHeader() throws IOException {
        OggPage firstPage = readRawPage();

        byte[] data = firstPage.data();
        if (data.length < 19) {
            throw new IOException("OpusHead packet too short");
        }

        for (int i = 0; i < 8; i++) {
            if (data[i] != OPUS_HEAD_MAGIC[i]) {
                throw new IOException("Invalid OpusHead magic");
            }
        }

        int version = data[8] & 0xFF;
        if (version > 1) {
            throw new IOException("Unsupported OpusHead version: " + version);
        }

        int channels = data[9] & 0xFF;
        int preSkip = readU16LE(data, 10);
        int sampleRate = readU32LE(data, 12);

        this.header = new OggHeader(channels, sampleRate, preSkip);
        this.headerParsed = true;
    }

    private void skipOpusTags() throws IOException {
        readRawPage();
    }

    private OggPage readRawPage() throws IOException {
        byte[] capture = readBytes(4);
        if (capture[0] != CAPTURE_PATTERN[0] || capture[1] != CAPTURE_PATTERN[1] ||
            capture[2] != CAPTURE_PATTERN[2] || capture[3] != CAPTURE_PATTERN[3]) {
            throw new IOException("Invalid OGG capture pattern");
        }

        int version = readByte();
        if (version != 0) {
            throw new IOException("Unsupported OGG version: " + version);
        }

        readByte(); // header type flags
        long granulePosition = readU64();
        int serial = readU32();
        readU32(); // page sequence number
        readU32(); // CRC checksum

        int numSegments = readByte() & 0xFF;
        int[] segmentSizes = new int[numSegments];
        int totalPayloadSize = 0;
        for (int i = 0; i < numSegments; i++) {
            segmentSizes[i] = readByte() & 0xFF;
            totalPayloadSize += segmentSizes[i];
        }

        byte[] payload = readBytes(totalPayloadSize);
        return new OggPage(payload, granulePosition, serial);
    }

    private int readByte() throws IOException {
        int b = input.read();
        if (b == -1) {
            throw new EOFException();
        }
        return b;
    }

    private byte[] readBytes(int count) throws IOException {
        byte[] buf = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = input.read(buf, offset, count - offset);
            if (read == -1) {
                throw new EOFException();
            }
            offset += read;
        }
        return buf;
    }

    private int readU16() throws IOException {
        byte[] b = readBytes(2);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
    }

    private int readU32() throws IOException {
        byte[] b = readBytes(4);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) |
               ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }

    private long readU64() throws IOException {
        byte[] b = readBytes(8);
        long val = 0;
        for (int i = 0; i < 8; i++) {
            val |= ((long) (b[i] & 0xFF)) << (i * 8);
        }
        return val;
    }

    private static int readU16LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readU32LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) |
               ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
