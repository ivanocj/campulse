package com.ivanocj.cameras.availability.application.service;

import com.ivanocj.cameras.availability.application.logging.LogContext;
import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.SessionRequest;
import com.ivanocj.cameras.availability.application.port.DatagramTransportFactory;
import com.ivanocj.cameras.availability.application.port.HostResolver;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.application.port.SessionProbeFactory;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-call orchestration; injected factories, resolver, and clock must support concurrent checks. */
public final class AvailabilityCheckService implements ProbeRunner {
    private static final Logger LOG = LoggerFactory.getLogger(AvailabilityCheckService.class);
    private static final long DNS_POLL_MILLIS = 50;
    private static final InetSocketAddress LAN_ROUTE = new InetSocketAddress("255.255.255.255", 32108);

    private final DatagramTransportFactory transports;
    private final HostResolver resolver;
    private final LongSupplier nanoTime;
    private final SessionProbeFactory probes;

    public AvailabilityCheckService(DatagramTransportFactory transports, HostResolver resolver, LongSupplier nanoTime,
                                    SessionProbeFactory probes) {
        this.transports = transports;
        this.resolver = resolver;
        this.nanoTime = nanoTime;
        this.probes = probes;
    }

    @Override
    public ProbeResult check(ProbeRequest request, BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        if (!request.protocol().legacyUdp()) {
            throw new IllegalArgumentException("Route " + request.protocol() + " to its own availability backend.");
        }
        try (var operation = LogContext.probe()) {
            LOG.info("event=probe_started protocol={} timeout_ms={} lan_search={} server_count={}",
                    request.protocol(), request.timeout().toMillis(), request.lanSearch(), request.servers().size());
            try {
                var result = performCheck(request, cancelled, progress);
                var information = result.p2pInformation();
                LOG.info("event=probe_completed status={} connection_mode={} elapsed_ms={} server_responded={} "
                                + "responding_servers={} device_candidates={} relay_endpoints={}",
                        result.status(), information.mode(), result.elapsed().toMillis(), result.serverResponded(),
                        information.respondingServers().size(), information.deviceCandidates().size(),
                        information.relayCandidates().size());
                return result;
            } catch (IOException | RuntimeException | Error failed) {
                LOG.error("event=probe_failed protocol={}", request.protocol(), failed);
                throw failed;
            }
        }
    }

    private ProbeResult performCheck(ProbeRequest request, BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        long started = nanoTime.getAsLong();
        long deadline = started + request.timeout().toNanos();
        // Resolution may use at most half the budget so the probe itself always gets time to run.
        var resolved = resolve(request, cancelled, started + request.timeout().toNanos() / 2, progress);
        if (cancelled.getAsBoolean() || (resolved.isEmpty() && !request.lanSearch())) {
            boolean wasCancelled = cancelled.getAsBoolean();
            return new ProbeResult(wasCancelled ? ProbeResult.Status.CANCELLED : ProbeResult.Status.INCONCLUSIVE,
                    wasCancelled ? "Check cancelled during DNS resolution."
                            : "Server resolution exceeded the deadline. Device availability is unknown.",
                    false, Optional.empty(), Optional.empty(), Duration.ofNanos(nanoTime.getAsLong() - started),
                    new P2pInformation(Optional.of(request.protocol()), P2pInformation.ConnectionMode.NONE,
                            Optional.empty(), List.of(), List.of(), List.of(), List.of(), Map.of()));
        }
        var servers = resolved.orElse(List.of());
        if (servers.isEmpty() && request.lanSearch()) {
            progress.accept("No resolved servers; checking the local network independently.");
        }
        boolean wasCancelled = cancelled.getAsBoolean();
        if (wasCancelled || nanoTime.getAsLong() >= deadline) {
            return new ProbeResult(wasCancelled ? ProbeResult.Status.CANCELLED : ProbeResult.Status.INCONCLUSIVE,
                    wasCancelled ? "Check cancelled before socket setup."
                            : "Overall deadline expired before socket setup. Device availability is unknown.",
                    false, Optional.empty(), Optional.empty(), Duration.ofNanos(nanoTime.getAsLong() - started),
                    new P2pInformation(Optional.of(request.protocol()), P2pInformation.ConnectionMode.NONE,
                            Optional.empty(), servers, List.of(), List.of(), List.of(), Map.of()));
        }
        if (servers.isEmpty() && !request.lanSearch()) {
            throw new IOException("Could not resolve any " + request.protocol() + " IPv4 server.");
        }
        try (var transport = transports.open(servers.isEmpty() ? LAN_ROUTE : servers.getFirst())) {
            var session = new SessionRequest(request.did(), servers,
                    Duration.ofNanos(Math.max(0, deadline - nanoTime.getAsLong())), request.lanSearch(), request.protocol());
            var result = probes.create(transport, nanoTime).check(session, cancelled, progress);
            return new ProbeResult(result.status(), result.detail(), result.serverResponded(), result.peer(),
                    result.publicEndpoint(), Duration.ofNanos(nanoTime.getAsLong() - started), result.p2pInformation());
        }
    }

    /**
     * Resolves every configured host concurrently. Returns empty when cancelled, or when the DNS budget ran out
     * before any host resolved; otherwise the IPv4 endpoints that did resolve, which may be none.
     */
    private Optional<List<InetSocketAddress>> resolve(ProbeRequest request, BooleanSupplier cancelled, long dnsDeadline,
                                                      Consumer<String> progress) throws IOException {
        if (cancelled.getAsBoolean() || nanoTime.getAsLong() >= dnsDeadline) {
            return Optional.empty();
        }
        if (request.servers().isEmpty()) {
            return Optional.of(List.of());
        }
        var lookups = new LinkedHashMap<InetSocketAddress, FutureTask<InetAddress[]>>();
        var loggingContext = LogContext.snapshot();
        for (var server : request.servers()) {
            lookups.computeIfAbsent(server, unresolved -> {
                int index = lookups.size() + 1;
                var lookup = new FutureTask<>(() -> {
                    try (var inherited = LogContext.inherit(loggingContext)) {
                        LOG.debug("event=dns_lookup_started server_index={}", index);
                        return resolver.resolve(unresolved.getHostString());
                    }
                });
                Thread.ofVirtual().name("probe-dns-" + index).start(lookup);
                return lookup;
            });
        }
        try {
            awaitLookups(lookups.values(), cancelled, dnsDeadline);
            if (cancelled.getAsBoolean()) {
                return Optional.empty();
            }
            var servers = new ArrayList<InetSocketAddress>();
            boolean unfinished = false;
            int index = 0;
            for (var entry : lookups.entrySet()) {
                index++;
                String host = entry.getKey().getHostString();
                var lookup = entry.getValue();
                if (!lookup.isDone()) {
                    unfinished = true;
                    LOG.warn("event=dns_lookup_timed_out server_index={}", index);
                    progress.accept("Resolving " + host + " took too long; skipped.");
                    continue;
                }
                try {
                    var ipv4 = Arrays.stream(lookup.get()).filter(Inet4Address.class::isInstance).distinct().toList();
                    if (!ipv4.isEmpty()) {
                        for (var address : ipv4) {
                            var endpoint = new InetSocketAddress(address, entry.getKey().getPort());
                            if (!servers.contains(endpoint)) {
                                servers.add(endpoint);
                            }
                        }
                        LOG.debug("event=dns_lookup_completed server_index={} ipv4=true", index);
                    } else {
                        LOG.warn("event=dns_lookup_no_ipv4 server_index={}", index);
                        progress.accept(host + " has no IPv4 address; skipped.");
                    }
                } catch (ExecutionException failed) {
                    LOG.warn("event=dns_lookup_failed server_index={}", index, failed.getCause());
                    progress.accept("Could not resolve " + host + ": " + describe(failed.getCause()));
                }
            }
            return servers.isEmpty() && unfinished ? Optional.empty() : Optional.of(servers);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving servers.", interrupted);
        } finally {
            lookups.values().forEach(lookup -> lookup.cancel(true));
        }
    }

    private void awaitLookups(Iterable<FutureTask<InetAddress[]>> lookups, BooleanSupplier cancelled, long dnsDeadline)
            throws InterruptedException {
        for (var lookup : lookups) {
            while (!lookup.isDone()) {
                if (cancelled.getAsBoolean() || nanoTime.getAsLong() >= dnsDeadline) {
                    return;
                }
                try {
                    // Poll so a platform DNS lookup cannot prevent UI cancellation.
                    lookup.get(Math.min(TimeUnit.MILLISECONDS.toNanos(DNS_POLL_MILLIS),
                            Math.max(1, dnsDeadline - nanoTime.getAsLong())), TimeUnit.NANOSECONDS);
                } catch (TimeoutException | ExecutionException pendingOrFailed) {
                    // Failures are reported per host once every lookup has settled.
                }
            }
        }
    }

    private static String describe(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }
}
