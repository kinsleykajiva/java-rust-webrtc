package io.github.kinsleykajiva.webrtc;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.EOFException;

public final class Mp3Reader implements AutoCloseable {

    public record Mp3Frame(byte[] data, int sampleRate, int channels, long durationMicros) {}

    private static final int[][] BITRATE_TABLE = {
            {0, 0, 0, 0, 0, 0},
            {32, 32, 40, 48, 56, 80},
            {40, 48, 56, 64, 80, 96},
            {48, 56, 64, 80, 96, 112},
            {56, 64, 80, 96, 112, 128},
            {64, 80, 96, 112, 128, 160},
            {80, 96, 112, 128, 160, 192},
            {96, 112, 128, 160, 192, 224},
            {112, 128, 160, 192, 224, 256},
            {128, 160, 192, 224, 256, 320},
            {160, 192, 224, 256, 320, 384},
            {192, 224, 256, 320, 384, 448},
            {224, 256, 320, 384, 448, 512},
            {256, 320, 384, 448, 512, 576},
            {320, 384, 448, 512, 576, 640}
    };

    private static final int[] SAMPLE_RATE_TABLE = {11025, 12000, 8000};

    private final BufferedInputStream input;

    public Mp3Reader(InputStream input) {
        this.input = new BufferedInputStream(input);
    }

    public Mp3Frame nextFrame() throws IOException {
        while (true) {
            int b0 = input.read();
            if (b0 == -1) {
                throw new EOFException("End of stream while searching for sync word");
            }
            if (b0 != 0xFF) {
                continue;
            }
            int b1 = input.read();
            if (b1 == -1) {
                throw new EOFException("End of stream after sync word");
            }
            if ((b1 & 0xE0) != 0xE0) {
                continue;
            }

            int b2 = input.read();
            int b3 = input.read();
            if (b2 == -1 || b3 == -1) {
                throw new EOFException("End of stream while reading frame header");
            }

            int header = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;

            int mpegVersion = (header >> 19) & 0x3;
            int layer = (header >> 17) & 0x3;
            int bitrateIndex = (header >> 12) & 0xF;
            int sampleRateIndex = (header >> 10) & 0x3;
            int padding = (header >> 9) & 0x1;
            int channelMode = (header >> 6) & 0x3;

            if (layer != 1) {
                continue;
            }
            if (mpegVersion == 1) {
                continue;
            }
            if (bitrateIndex == 0 || bitrateIndex == 15) {
                continue;
            }
            if (sampleRateIndex == 3) {
                continue;
            }

            int versionCol;
            if (mpegVersion == 3) {
                versionCol = 0;
            } else if (mpegVersion == 2) {
                versionCol = 1;
            } else {
                versionCol = 2;
            }
            int layerCol = 3 - layer;

            int tableIndex;
            if (versionCol == 0 && layerCol == 0) {
                tableIndex = 5;
            } else if (layerCol == 0) {
                tableIndex = 4;
            } else if (versionCol == 0 && layerCol == 1) {
                tableIndex = 3;
            } else if (layerCol == 1) {
                tableIndex = 2;
            } else if (versionCol == 0 && layerCol == 2) {
                tableIndex = 1;
            } else {
                tableIndex = 0;
            }

            int bitrate = BITRATE_TABLE[bitrateIndex][tableIndex] * 1000;

            int srIndex = sampleRateIndex;
            if (mpegVersion == 3) {
                srIndex = 0;
            } else if (mpegVersion == 2) {
                srIndex = 1;
            } else {
                srIndex = 2;
            }
            int sampleRate = SAMPLE_RATE_TABLE[srIndex];

            int frameSize = (144 * bitrate) / sampleRate + padding;

            byte[] data = new byte[frameSize];
            int read = 0;
            while (read < frameSize) {
                int n = input.read(data, read, frameSize - read);
                if (n == -1) {
                    throw new EOFException("End of stream while reading frame data");
                }
                read += n;
            }

            int channels;
            if (channelMode == 3) {
                channels = 1;
            } else {
                channels = 2;
            }

            long durationMicros = (long) data.length * 1_000_000L * 8 / bitrate;

            return new Mp3Frame(data, sampleRate, channels, durationMicros);
        }
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}
