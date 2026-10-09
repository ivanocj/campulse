package com.ivanocj.cameras.availability.infrastructure.transport;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** One monotonic budget shared by resolution, discovery, authentication, query and cleanup. */
public final class YooseeDeadline {
    private final long end;
    private final BooleanSupplier cancelled;
    public YooseeDeadline(Duration duration, BooleanSupplier cancelled) {
        if (duration.isNegative() || duration.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("Yoosee timeout must be between zero and 120 seconds.");
        }
        end = System.nanoTime() + duration.toNanos();
        this.cancelled = cancelled;
    }
    private YooseeDeadline(long end, BooleanSupplier cancelled) { this.end = end; this.cancelled = cancelled; }
    public YooseeDeadline phase(Duration maximum) { return new YooseeDeadline(System.nanoTime() + Math.min(remaining(), maximum.toNanos()), cancelled); }
    public long remaining() { return Math.max(0, end - System.nanoTime()); }
    public boolean cancelled() { return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted(); }
    public void check() throws InterruptedIOException {
        if (cancelled()) { throw new InterruptedIOException("Yoosee check cancelled."); }
        if (remaining() == 0) { throw new SocketTimeoutException("Yoosee session deadline expired; presence is unknown."); }
    }
    public int pollMillis() throws InterruptedIOException { check(); return (int) Math.max(1, Math.min(50, remaining() / 1_000_000)); }
}
