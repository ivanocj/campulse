package com.ivanocj.cameras.availability.application.service;

import com.ivanocj.cameras.availability.application.logging.LogContext;
import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.LanDiscoveryResult;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.port.LanDiscovery;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GwellAvailabilityService implements ProbeRunner {
    private static final Logger LOG = LoggerFactory.getLogger(GwellAvailabilityService.class);
    private final LanDiscovery discovery;
    private final ProbeRunner remote;

    public GwellAvailabilityService(LanDiscovery discovery, ProbeRunner remote) {
        this.discovery = discovery;
        this.remote = remote;
    }

    @Override
    public ProbeResult check(ProbeRequest request, BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        if (request.protocol() != TransportProtocol.GWELL) {
            throw new IllegalArgumentException("GWell availability requires a GWell identifier/backend.");
        }
        if (!request.lanSearch()) {
            return remote.check(request, cancelled, progress);
        }
        try (var operation = LogContext.probe()) {
            LOG.info("event=probe_started protocol=GWELL timeout_ms={} lan_search=true server_count={}", request.timeout().toMillis(), request.servers().size());
            long started = System.nanoTime();
            LanDiscoveryResult scanned;
            try {
                scanned = discovery.scan(request.servers().isEmpty() ? request.timeout() : request.timeout().dividedBy(2), cancelled, progress);
            } catch (IOException failed) {
                if (request.servers().isEmpty()) { throw failed; }
                progress.accept("GWell LAN discovery could not complete; remote presence will be checked independently.");
                scanned = new LanDiscoveryResult(List.of(), cancelled.getAsBoolean() || Thread.currentThread().isInterrupted(),
                        Duration.ofNanos(System.nanoTime() - started), Optional.empty());
            }
            int identity = ((GwellDeviceId) request.identifier()).wireValue();
            var matches = scanned.devices().stream().filter(device -> device.identifier().wireValue() == identity).toList();
            var status = scanned.cancelled() || cancelled.getAsBoolean() ? ProbeResult.Status.CANCELLED
                    : matches.isEmpty() ? ProbeResult.Status.INCONCLUSIVE : ProbeResult.Status.DISCOVERED_LAN;
            var detail = switch (status) {
                case CANCELLED -> "Check cancelled. Retained LAN replies are discovery evidence only.";
                case DISCOVERED_LAN -> "Matching GWell device discovered on LAN. The reply is unauthenticated; no P2P/alive exchange was tested.";
                default -> "No matching GWell LAN reply. Discovery alone cannot establish device availability.";
            };
            var information = new P2pInformation(Optional.of(TransportProtocol.GWELL), P2pInformation.ConnectionMode.NONE,
                    scanned.localEndpoint(), List.of(), List.of(), matches.stream().map(device -> device.endpoint()).distinct().toList(),
                    List.of(), Map.of());
            LOG.info("event=lan_scan_completed protocol=GWELL status={} elapsed_ms={} matching_lan_endpoints={}",
                    status, scanned.elapsed().toMillis(), matches.size());
            if (request.servers().isEmpty() || status == ProbeResult.Status.CANCELLED) {
                return new ProbeResult(status, detail, false, Optional.empty(), Optional.empty(), scanned.elapsed(), information, matches);
            }
            var remaining = request.timeout().minusNanos(Math.min(request.timeout().toNanos(), System.nanoTime() - started));
            var remoteResult = remote.check(new ProbeRequest(request.identifier(), request.servers(), remaining, false, request.protocol()),
                    cancelled, progress);
            var remoteInfo = remoteResult.p2pInformation();
            var mergedInfo = new P2pInformation(remoteInfo.protocol(), remoteInfo.mode(), remoteInfo.localEndpoint(),
                    remoteInfo.resolvedServers(), remoteInfo.respondingServers(), information.deviceCandidates(),
                    remoteInfo.relayCandidates(), remoteInfo.serverStatuses());
            var mergedStatus = !matches.isEmpty() && remoteResult.remotePresence().isEmpty()
                    && (remoteResult.status() == ProbeResult.Status.INCONCLUSIVE || remoteResult.status() == ProbeResult.Status.TIMED_OUT)
                    ? ProbeResult.Status.DISCOVERED_LAN : remoteResult.status();
            return new ProbeResult(mergedStatus, remoteResult.detail() + (matches.isEmpty() ? "" : " Matching unauthenticated LAN replies retained separately."),
                    remoteResult.serverResponded(), Optional.empty(), Optional.empty(), Duration.ofNanos(System.nanoTime() - started),
                    mergedInfo, matches, remoteResult.remotePresence());
        }
    }
}
