package io.github.kinsleykajiva.webrtc;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.EOFException;

public final class WavReader implements AutoCloseable {

    public record WavHeader(int channels, int sampleRate, int bitsPerSample) {}

    private final BufferedInputStream input;
    private WavHeader header;
    private long dataRemaining;

    public WavReader(InputStream input) throws IOException {
        this.input = new BufferedInputStream(input);
        parseHeader();
    }

    private void parseHeader() throws IOException {
        byte[] buf = readFully(12);
        if (buf[0] != 'R' || buf[1] != 'I' || buf[2] != 'F' || buf[3] != 'F') {
            throw new IOException("Invalid RIFF header");
        }
        if (buf[8] != 'W' || buf[9] != 'A' || buf[10] != 'V' || buf[11] != 'E') {
            throw new IOException("Invalid WAVE format");
        }

        boolean foundFmt = false;
        boolean foundData = false;

        while (!foundData) {
            byte[] chunkHeader = readFully(8);
            String chunkId = new String(chunkHeader, 0, 4);
            int chunkSize = readU32LE(chunkHeader, 4);

            if (chunkId.equals("fmt ")) {
                byte[] fmt = readFully(chunkSize);
                int audioFormat = readU16LE(fmt, 0);
                if (audioFormat != 1) {
                    throw new IOException("Unsupported audio format: " + audioFormat + " (only PCM supported)");
                }
                int channels = readU16LE(fmt, 2);
                int sampleRate = readU32LE(fmt, 4);
                int bitsPerSample = readU16LE(fmt, 14);
                this.header = new WavHeader(channels, sampleRate, bitsPerSample);
                foundFmt = true;
            } else if (chunkId.equals("data")) {
                if (!foundFmt) {
                    throw new IOException("data chunk before fmt chunk");
                }
                this.dataRemaining = chunkSize & 0xFFFFFFFFL;
                foundData = true;
            } else {
                skipFully(chunkSize);
            }
        }

        if (header == null) {
            throw new IOException("No fmt chunk found");
        }
    }

    public WavHeader header() {
        return header;
    }

    public byte[] nextSamples(int numBytes) throws IOException {
        if (dataRemaining <= 0) {
            return new byte[0];
        }
        int toRead = (int) Math.min(numBytes, dataRemaining);
        byte[] data = readFully(toRead);
        dataRemaining -= toRead;
        return data;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = input.read(buf, read, n - read);
            if (r == -1) {
                throw new EOFException("Unexpected end of stream");
            }
            read += r;
        }
        return buf;
    }

    private void skipFully(long n) throws IOException {
        while (n > 0) {
            long skipped = input.skip(n);
            if (skipped <= 0) {
                if (input.read() == -1) {
                    throw new EOFException("Unexpected end of stream");
                }
                n--;
            } else {
                n -= skipped;
            }
        }
    }

    private static int readU16LE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int readU32LE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
