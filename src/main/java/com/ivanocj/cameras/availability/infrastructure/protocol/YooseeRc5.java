package com.ivanocj.cameras.availability.infrastructure.protocol;

import java.util.Arrays;

/** RC5 compatibility primitive; the SDK's six-round modes are not suitable for new security designs. */
public final class YooseeRc5 implements AutoCloseable {
    private final int blockBytes;
    private final int wordBytes;
    private final int rounds;
    private final long mask;
    private final long[] schedule;
    private boolean closed;

    public YooseeRc5(int blockBytes, int rounds, byte[] key) {
        if ((blockBytes != 8 && blockBytes != 16) || rounds < 1 || rounds > 255
                || key == null || key.length < 1 || key.length > 256) {
            throw new IllegalArgumentException("Invalid RC5 compatibility parameters.");
        }
        this.blockBytes = blockBytes;
        this.wordBytes = blockBytes / 2;
        this.rounds = rounds;
        mask = wordBytes == 4 ? 0xffffffffL : -1L;
        schedule = new long[2 * (rounds + 1)];
        var words = new long[(key.length + wordBytes - 1) / wordBytes];
        for (int i = 0; i < key.length; i++) {
            words[i / wordBytes] |= (long) (key[i] & 255) << ((i % wordBytes) * 8);
        }
        schedule[0] = wordBytes == 4 ? 0xb7e15163L : 0xb7e151628aed2a6bL;
        long increment = wordBytes == 4 ? 0x9e3779b9L : 0x9e3779b97f4a7c15L;
        for (int i = 1; i < schedule.length; i++) {
            schedule[i] = (schedule[i - 1] + increment) & mask;
        }
        long a = 0, b = 0;
        int i = 0, j = 0;
        for (int k = 0; k < 3 * Math.max(schedule.length, words.length); k++) {
            a = schedule[i] = rotateLeft((schedule[i] + a + b) & mask, 3);
            b = words[j] = rotateLeft((words[j] + a + b) & mask, a + b);
            i = (i + 1) % schedule.length;
            j = (j + 1) % words.length;
        }
        Arrays.fill(words, 0);
    }

    public void encrypt(byte[] block, int offset) {
        validate(block, offset);
        long a = (read(block, offset) + schedule[0]) & mask;
        long b = (read(block, offset + wordBytes) + schedule[1]) & mask;
        for (int i = 1; i <= rounds; i++) {
            a = (rotateLeft(a ^ b, b) + schedule[2 * i]) & mask;
            b = (rotateLeft(b ^ a, a) + schedule[2 * i + 1]) & mask;
        }
        write(block, offset, a);
        write(block, offset + wordBytes, b);
    }

    public void decrypt(byte[] block, int offset) {
        validate(block, offset);
        long a = read(block, offset), b = read(block, offset + wordBytes);
        for (int i = rounds; i >= 1; i--) {
            b = rotateRight((b - schedule[2 * i + 1]) & mask, a) ^ a;
            a = rotateRight((a - schedule[2 * i]) & mask, b) ^ b;
        }
        write(block, offset, (a - schedule[0]) & mask);
        write(block, offset + wordBytes, (b - schedule[1]) & mask);
    }

    private long rotateLeft(long value, long distance) {
        return wordBytes == 4 ? Integer.toUnsignedLong(Integer.rotateLeft((int) value, (int) distance))
                : Long.rotateLeft(value, (int) distance);
    }

    private long rotateRight(long value, long distance) {
        return wordBytes == 4 ? Integer.toUnsignedLong(Integer.rotateRight((int) value, (int) distance))
                : Long.rotateRight(value, (int) distance);
    }

    private long read(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < wordBytes; i++) { value |= (long) (data[offset + i] & 255) << (i * 8); }
        return value;
    }

    private void write(byte[] data, int offset, long value) {
        for (int i = 0; i < wordBytes; i++) { data[offset + i] = (byte) (value >>> (i * 8)); }
    }

    private void validate(byte[] block, int offset) {
        if (closed) { throw new IllegalStateException("RC5 key has been erased."); }
        if (block == null || offset < 0 || offset > block.length - blockBytes) {
            throw new IllegalArgumentException("RC5 needs a complete block.");
        }
    }

    @Override public void close() {
        Arrays.fill(schedule, 0);
        closed = true;
    }
}
