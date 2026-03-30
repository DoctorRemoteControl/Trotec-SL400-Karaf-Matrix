package de.drremote.trotecsl400.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WavReader {
    private WavReader() {
    }

    public static short[] readPcm16(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        int dataOffset = findDataOffset(bytes);
        if (dataOffset < 0 || dataOffset + 4 >= bytes.length) {
            throw new IllegalArgumentException("Invalid WAV: missing data chunk");
        }
        int dataSize = readIntLE(bytes, dataOffset);
        int start = dataOffset + 4;
        int end = Math.min(bytes.length, start + dataSize);
        int sampleCount = (end - start) / 2;
        short[] samples = new short[sampleCount];
        ByteBuffer buf = ByteBuffer.wrap(bytes, start, end - start).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = buf.getShort();
        }
        return samples;
    }

    private static int findDataOffset(byte[] bytes) {
        for (int i = 12; i + 8 < bytes.length; ) {
            String chunkId = new String(bytes, i, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = readIntLE(bytes, i + 4);
            if ("data".equals(chunkId)) {
                return i + 4;
            }
            i += 8 + size;
        }
        return -1;
    }

    private static int readIntLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
