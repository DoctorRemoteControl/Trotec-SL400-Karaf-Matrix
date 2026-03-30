package de.drremote.trotecsl400.runtime;

public class AudioRingBuffer {
    private final byte[] buffer;
    private final int capacity;
    private int writePos;
    private long totalWritten;

    public AudioRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.buffer = new byte[capacity];
    }

    public synchronized void write(byte[] data, int length) {
        if (length <= 0) return;
        int offset = 0;
        int remaining = length;
        while (remaining > 0) {
            int chunk = Math.min(remaining, capacity - writePos);
            System.arraycopy(data, offset, buffer, writePos, chunk);
            writePos = (writePos + chunk) % capacity;
            offset += chunk;
            remaining -= chunk;
        }
        totalWritten += length;
    }

    public synchronized long totalBytesWritten() {
        return totalWritten;
    }

    public synchronized byte[] readLatest(int length) {
        if (length <= 0) {
            return new byte[0];
        }
        int available = (int) Math.min(totalWritten, capacity);
        int len = Math.min(length, available);
        long start = totalWritten - len;
        return readRange(start, len);
    }

    public synchronized byte[] readRange(long startOffset, int length) {
        if (length <= 0) {
            return new byte[0];
        }
        long earliest = totalWritten - capacity;
        if (startOffset < earliest) {
            int diff = (int) (earliest - startOffset);
            startOffset = earliest;
            length -= diff;
        }
        long available = totalWritten - startOffset;
        if (length > available) {
            length = (int) available;
        }
        if (length <= 0) {
            return new byte[0];
        }
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            long pos = startOffset + i;
            int idx = (int) (pos % capacity);
            out[i] = buffer[idx];
        }
        return out;
    }
}
