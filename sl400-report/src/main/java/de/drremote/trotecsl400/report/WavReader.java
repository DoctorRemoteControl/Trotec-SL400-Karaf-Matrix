package de.drremote.trotecsl400.report;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

final class WavReader {
    private WavReader() {
    }

    static short[] readPcm16(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        int dataOffset = findChunkOffset(bytes, "data");
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

    static int readSampleRate(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        int fmtOffset = findChunkOffset(bytes, "fmt ");
        if (fmtOffset < 0 || fmtOffset + 20 >= bytes.length) {
            throw new IllegalArgumentException("Invalid WAV: missing fmt chunk");
        }
        int audioFormat = readShortLE(bytes, fmtOffset + 4);
        int channels = readShortLE(bytes, fmtOffset + 6);
        int bitsPerSample = readShortLE(bytes, fmtOffset + 18);
        if (audioFormat != 1 || bitsPerSample != 16 || channels <= 0) {
            throw new IllegalArgumentException("Unsupported WAV format (expected PCM16)");
        }
        return readIntLE(bytes, fmtOffset + 8);
    }

    static int readChannelCount(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        int fmtOffset = findChunkOffset(bytes, "fmt ");
        if (fmtOffset < 0 || fmtOffset + 16 >= bytes.length) {
            throw new IllegalArgumentException("Invalid WAV: missing fmt chunk");
        }
        return readShortLE(bytes, fmtOffset + 6);
    }

    private static int findChunkOffset(byte[] bytes, String chunkId) {
        for (int i = 12; i + 8 < bytes.length; ) {
            String id = new String(bytes, i, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = readIntLE(bytes, i + 4);
            if (chunkId.equals(id)) {
                return i + 4;
            }
            i += 8 + size + (size & 1);
        }
        return -1;
    }

    private static int readIntLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static int readShortLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }
}
