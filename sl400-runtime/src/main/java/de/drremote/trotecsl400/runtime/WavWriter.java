package de.drremote.trotecsl400.runtime;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WavWriter {
    private WavWriter() {
    }

    public static void writePcmWav(Path file, byte[] pcmData, AudioFormat format) throws IOException {
        int channels = format.getChannels();
        int sampleRate = (int) format.getSampleRate();
        int bitsPerSample = format.getSampleSizeInBits();
        int blockAlign = channels * bitsPerSample / 8;
        int byteRate = sampleRate * blockAlign;
        int dataSize = pcmData.length;
        int chunkSize = 36 + dataSize;

        try (OutputStream out = Files.newOutputStream(file)) {
            writeAscii(out, "RIFF");
            writeIntLE(out, chunkSize);
            writeAscii(out, "WAVE");
            writeAscii(out, "fmt ");
            writeIntLE(out, 16);
            writeShortLE(out, (short) 1);
            writeShortLE(out, (short) channels);
            writeIntLE(out, sampleRate);
            writeIntLE(out, byteRate);
            writeShortLE(out, (short) blockAlign);
            writeShortLE(out, (short) bitsPerSample);
            writeAscii(out, "data");
            writeIntLE(out, dataSize);
            out.write(pcmData);
        }
    }

    private static void writeAscii(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeIntLE(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeShortLE(OutputStream out, short value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
