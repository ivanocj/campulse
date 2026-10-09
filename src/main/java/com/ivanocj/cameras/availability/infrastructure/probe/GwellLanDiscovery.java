package com.ivanocj.cameras.availability.infrastructure.probe;

import com.ivanocj.cameras.availability.application.logging.LogContext;
import com.ivanocj.cameras.availability.application.model.GwellLanDevice;
import com.ivanocj.cameras.availability.application.model.LanDiscoveryResult;
import com.ivanocj.cameras.availability.application.port.DatagramTransportFactory;
import com.ivanocj.cameras.availability.application.port.LanDiscovery;
import com.ivanocj.cameras.availability.infrastructure.protocol.GwellDiscoveryMessages;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One UDP-8899 listener per shared discovery adapter, with cancellable, deadline-bounded queueing. */
public final class GwellLanDiscovery implements LanDiscovery {
    private static final Logger LOG = LoggerFactory.getLogger(GwellLanDiscovery.class);
    private static final long POLL_NANOS = Duration.ofMillis(100).toNanos();
    private static final long RETRY_NANOS = Duration.ofSeconds(1).toNanos();
    private static final int MAX_DEVICES = 256;
    private final DatagramTransportFactory transports;
    private final Supplier<List<InetSocketAddress>> targets;
    private final LongSupplier nanoTime;
    private final ReentrantLock listener = new ReentrantLock(true);

    public GwellLanDiscovery(DatagramTransportFactory transports, Supplier<List<InetSocketAddress>> targets,
                              LongSupplier nanoTime) {
        this.transports = transports;
        this.targets = targets;
        this.nanoTime = nanoTime;
    }

    @Override
    public LanDiscoveryResult scan(Duration timeout, BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        if (!timeout.isPositive()) {
            throw new IllegalArgumentException("LAN discovery needs a positive timeout.");
        }
        long started = nanoTime.getAsLong();
        long deadline = started + timeout.toNanos();
        try (var operation = LogContext.probe()) {
            while (!cancelled.getAsBoolean() && nanoTime.getAsLong() < deadline) {
                try {
                    if (listener.tryLock(Math.min(POLL_NANOS, Math.max(1, deadline - nanoTime.getAsLong())),
                            TimeUnit.NANOSECONDS)) {
                        try {
                            if (cancelled.getAsBoolean() || nanoTime.getAsLong() >= deadline) {
                                break;
                            }
                            return scanOwned(started, deadline, cancelled, progress);
                        } finally {
                            listener.unlock();
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for GWell LAN discovery.", interrupted);
                }
            }
            return new LanDiscoveryResult(List.of(), cancelled.getAsBoolean(), elapsed(started), Optional.empty());
        }
    }

    private LanDiscoveryResult scanOwned(long started, long deadline, BooleanSupplier cancelled,
                                          Consumer<String> progress) throws IOException {
        var destinations = List.copyOf(new LinkedHashSet<>(targets.get()));
        if (cancelled.getAsBoolean() || nanoTime.getAsLong() >= deadline) {
            return new LanDiscoveryResult(List.of(), cancelled.getAsBoolean(), elapsed(started), Optional.empty());
        }
        if (destinations.isEmpty() || destinations.stream().anyMatch(target -> !(target.getAddress() instanceof Inet4Address)
                || target.getPort() == 0)) {
            throw new IOException("No usable IPv4 LAN discovery targets.");
        }
        var devices = new LinkedHashMap<DeviceEndpoint, GwellLanDevice>();
        var failedTargets = new HashSet<InetSocketAddress>();
        int ignored = 0;
        try (var transport = transports.open(destinations.getFirst())) {
            LOG.debug("event=gwell_discovery_started target_count={}", destinations.size());
            progress.accept("Searching for GWell devices on LAN · UDP 8899. Discovery is not P2P confirmation.");
            long nextSend = nanoTime.getAsLong();
            while (!cancelled.getAsBoolean() && nanoTime.getAsLong() < deadline) {
                if (nanoTime.getAsLong() >= nextSend) {
                    boolean sent = false;
                    IOException lastFailure = null;
                    for (var destination : destinations) {
                        if (cancelled.getAsBoolean() || nanoTime.getAsLong() >= deadline) {
                            break;
                        }
                        try {
                            transport.send(GwellDiscoveryMessages.searchRequest(), destination);
                            failedTargets.remove(destination);
                            sent = true;
                        } catch (IOException failed) {
                            lastFailure = failed;
                            if (failedTargets.add(destination)) {
                                LOG.warn("event=gwell_discovery_send_failed");
                                progress.accept("Could not send LAN discovery to " + destination + ": " + failed.getMessage());
                            }
                        }
                    }
                    if (!sent && lastFailure != null && !cancelled.getAsBoolean() && nanoTime.getAsLong() < deadline) {
                        throw new IOException("Could not send GWell discovery on any LAN interface.", lastFailure);
                    }
                    nextSend = nanoTime.getAsLong() + RETRY_NANOS;
                }
                long remaining = deadline - nanoTime.getAsLong();
                if (remaining <= 0 || cancelled.getAsBoolean()) {
                    break;
                }
                var received = transport.receive(Duration.ofNanos(Math.min(POLL_NANOS, remaining)));
                if (cancelled.getAsBoolean() || nanoTime.getAsLong() >= deadline) {
                    break;
                }
                if (received.isPresent()) {
                    var datagram = received.get();
                    if (!localSource(datagram.source())) {
                        ignored++;
                        continue;
                    }
                    GwellLanDevice device;
                    try {
                        device = GwellDiscoveryMessages.readReply(datagram.bytes(), datagram.source());
                    } catch (IllegalArgumentException malformed) {
                        ignored++;
                        continue;
                    }
                    var key = new DeviceEndpoint(device.identifier().wireValue(), device.endpoint());
                    if (devices.containsKey(key) || devices.size() < MAX_DEVICES) {
                        if (devices.put(key, device) == null) {
                            progress.accept("Discovered GWell " + device.identifier().value() + " at " + device.endpoint());
                        }
                    }
                }
            }
            LOG.debug("event=gwell_discovery_completed discovered={} ignored_packets={} cancelled={}",
                    devices.size(), ignored, cancelled.getAsBoolean());
            return new LanDiscoveryResult(List.copyOf(devices.values()), cancelled.getAsBoolean(), elapsed(started),
                    Optional.of(transport.localAddress()));
        }
    }

    private static boolean localSource(InetSocketAddress source) {
        var address = source.getAddress();
        return source.getPort() > 0 && address instanceof Inet4Address
                && (address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress());
    }

    private Duration elapsed(long started) {
        return Duration.ofNanos(nanoTime.getAsLong() - started);
    }

    private record DeviceEndpoint(int identity, InetSocketAddress endpoint) {
    }
}
