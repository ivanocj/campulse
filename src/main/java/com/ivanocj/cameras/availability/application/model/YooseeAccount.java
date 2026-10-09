package com.ivanocj.cameras.availability.application.model;

import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/** Per-check credential snapshot. Diagnostics deliberately redact every account field. */
public sealed interface YooseeAccount extends AutoCloseable permits YooseeAccount.Gwell, YooseeAccount.IotVideo {
    Optional<Instant> expiresAt();
    @Override void close();

    final class Gwell implements YooseeAccount {
        private final int[] words;
        private final Optional<Instant> expiresAt;
        private boolean closed;

        public Gwell(GwellDeviceId userId, int sessionId1, int sessionId2, int code1, int code2,
                     Optional<Instant> expiresAt) {
            words = new int[]{userId.wireValue(), sessionId1, sessionId2, code1, code2};
            this.expiresAt = java.util.Objects.requireNonNull(expiresAt);
        }

        public long userId() { return Integer.toUnsignedLong(word(0)); }
        public int sessionId1() { return word(1); }
        public int sessionId2() { return word(2); }
        public int code1() { return word(3); }
        public int code2() { return word(4); }
        private int word(int index) {
            if (closed) { throw new IllegalStateException("Account snapshot was erased."); }
            return words[index];
        }
        @Override public Optional<Instant> expiresAt() { return expiresAt; }
        @Override public void close() { Arrays.fill(words, 0); closed = true; }
        @Override public String toString() { return "GwellAccount[redacted]"; }
    }

    final class IotVideo implements YooseeAccount {
        private long accessId;
        private final byte[] token;
        private final Optional<Instant> expiresAt;
        private boolean closed;

        public IotVideo(long accessId, byte[] token, Optional<Instant> expiresAt) {
            if (accessId == 0 || token == null || token.length != 64 || token[0] != 1) {
                throw new IllegalArgumentException("IoTVideo requires a nonzero access ID and a 128-hex-character type-1 account token.");
            }
            // Anonymous/device-token subscriptions have a different registration contract; never silently truncate them.
            this.accessId = accessId;
            this.token = token.clone();
            this.expiresAt = java.util.Objects.requireNonNull(expiresAt);
        }

        public long wireId() {
            ensureOpen();
            return (accessId & 0xe0ffffffffffffffL) | (long) ((token[1] >>> 1) & 31) << 56;
        }
        public byte[] token() { ensureOpen(); return token.clone(); }
        private void ensureOpen() {
            if (closed) { throw new IllegalStateException("Account snapshot was erased."); }
        }
        @Override public Optional<Instant> expiresAt() { return expiresAt; }
        @Override public void close() { Arrays.fill(token, (byte) 0); accessId = 0; closed = true; }
        @Override public String toString() { return "IotVideoAccount[redacted]"; }
    }
}
