package io.github.kinsleykajiva.webrtc;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads raw H.264 (Annex B) or H.265 bitstreams and returns individual access units.
 *
 * <p>Non-VCL NAL units (SPS, PPS, VPS, SEI, etc.) are batched and prepended to the
 * next VCL access unit. Each call to {@link #nextSample()} returns one access unit
 * together with a flag indicating whether the sample contains timing-relevant data
 * (VCL NAL units) and should be paced.</p>
 *
 * <h3>Content</h3>
 * <ul>
 *   <li>H.264: {@link MimeTypes#CONTENT_H264} — raw Annex B bitstream, construct with {@code isHevc=false}</li>
 *   <li>H.265: {@link MimeTypes#CONTENT_H265} — raw Annex B bitstream, construct with {@code isHevc=true}</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * try (var reader = new H26xReader(new FileInputStream(MimeTypes.CONTENT_H264), 1024*1024, false)) {
 *     H26xReader.H26xSample sample;
 *     while ((sample = reader.nextSample()) != null) {
 *         track.writeSample(payloadType, sample.data(), 33);
 *     }
 * }
 * }</pre>
 */
public final class H26xReader implements AutoCloseable {

    public record H26xSample(byte[] data, boolean timed) {}

    private final BufferedInputStream input;
    private final boolean isHevc;

    private boolean eos;
    private byte[] pendingNal;

    public H26xReader(InputStream input, int capacity, boolean isHevc) {
        this.input = new BufferedInputStream(input, capacity);
        this.isHevc = isHevc;
        this.eos = false;
    }

    /**
     * Returns the next access unit from the stream, or {@code null} at end-of-stream.
     */
    public H26xSample nextSample() throws IOException {
        if (eos) {
            return null;
        }

        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        boolean hasVcl = false;

        while (true) {
            byte[] nal = readNextNal();
            if (nal == null) {
                eos = true;
                break;
            }

            boolean vcl = isVclNal(nal);

            if (vcl) {
                hasVcl = true;
                acc.write(nal);
            } else if (!hasVcl) {
                acc.write(nal);
            } else {
                pendingNal = nal;
                break;
            }
        }

        if (acc.size() == 0) {
            return null;
        }

        return new H26xSample(acc.toByteArray(), hasVcl);
    }

    private byte[] readNextNal() throws IOException {
        if (pendingNal != null) {
            byte[] nal = pendingNal;
            pendingNal = null;
            return nal;
        }

        if (eos) {
            return null;
        }

        skipToStartCode();

        if (eos) {
            return null;
        }

        ByteArrayOutputStream nalBuf = new ByteArrayOutputStream();
        nalBuf.write(0x00);
        nalBuf.write(0x00);
        nalBuf.write(0x01);

        while (true) {
            int b = input.read();
            if (b == -1) {
                eos = true;
                return nalBuf.toByteArray();
            }

            int b1 = input.read();
            if (b1 == -1) {
                nalBuf.write(b);
                eos = true;
                return nalBuf.toByteArray();
            }

            int b2 = input.read();
            if (b2 == -1) {
                nalBuf.write(b);
                nalBuf.write(b1);
                eos = true;
                return nalBuf.toByteArray();
            }

            if (b == 0x00 && b1 == 0x00 && (b2 == 0x00 || b2 == 0x01)) {
                pendingNal = new byte[]{(byte) 0x00, (byte) 0x00, (byte) b2};
                return nalBuf.toByteArray();
            }

            nalBuf.write(b);
            nalBuf.write(b1);
            nalBuf.write(b2);
        }
    }

    private void skipToStartCode() throws IOException {
        while (!eos) {
            int b = input.read();
            if (b == -1) {
                eos = true;
                return;
            }
            if (b != 0x00) {
                continue;
            }
            int b1 = input.read();
            if (b1 == -1) {
                eos = true;
                return;
            }
            if (b1 != 0x00) {
                continue;
            }
            int b2 = input.read();
            if (b2 == -1) {
                eos = true;
                return;
            }
            if (b2 == 0x01) {
                return;
            }
            if (b2 != 0x00) {
                continue;
            }
            int b3 = input.read();
            if (b3 == -1) {
                eos = true;
                return;
            }
            if (b3 == 0x01) {
                return;
            }
        }
    }

    private boolean isVclNal(byte[] nal) {
        if (nal.length < 4) {
            return false;
        }
        int nalHeader = nal[3] & 0xFF;

        if (!isHevc) {
            int nalType = nalHeader & 0x1F;
            return nalType == 1 || nalType == 5;
        } else {
            int nalType = (nalHeader >> 1) & 0x3F;
            return (nalType >= 0 && nalType <= 9) || (nalType >= 16 && nalType <= 21);
        }
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    private static final class ByteArrayOutputStream {
        private byte[] buf;
        private int count;

        ByteArrayOutputStream() {
            this.buf = new byte[1024];
            this.count = 0;
        }

        void write(byte[] data) {
            ensureCapacity(count + data.length);
            System.arraycopy(data, 0, buf, count, data.length);
            count += data.length;
        }

        void write(int b) {
            ensureCapacity(count + 1);
            buf[count++] = (byte) b;
        }

        byte[] toByteArray() {
            byte[] result = new byte[count];
            System.arraycopy(buf, 0, result, 0, count);
            return result;
        }

        int size() {
            return count;
        }

        private void ensureCapacity(int minCapacity) {
            if (minCapacity > buf.length) {
                int newCapacity = Math.max(buf.length * 2, minCapacity);
                byte[] newBuf = new byte[newCapacity];
                System.arraycopy(buf, 0, newBuf, 0, count);
                buf = newBuf;
            }
        }
    }
}
