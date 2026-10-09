package com.ivanocj.cameras.availability.infrastructure.probe;

import com.ivanocj.cameras.availability.application.logging.LogContext;
import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.RemotePresence;
import com.ivanocj.cameras.availability.application.model.YooseeAccount;
import com.ivanocj.cameras.availability.application.port.HostResolver;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.application.port.YooseeCredentialSource;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.protocol.GuteFrameCodec;
import com.ivanocj.cameras.availability.infrastructure.transport.YooseeDeadline;
import com.ivanocj.cameras.availability.infrastructure.transport.YooseeTcpTransport;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common bounded I/O orchestration, not a common authentication/status protocol. */
abstract class AbstractYooseeRemoteProbe implements ProbeRunner {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractYooseeRemoteProbe.class);
    private final TransportProtocol protocol;
    private final YooseeCredentialSource credentials;
    private final HostResolver resolver;
    // The native account lifecycle owns one infrastructure registration, not one registration per camera.
    private final ReentrantLock ownership = new ReentrantLock(true);
    protected final Clock clock;

    AbstractYooseeRemoteProbe(TransportProtocol protocol, YooseeCredentialSource credentials, HostResolver resolver, Clock clock) {
        this.protocol = protocol;
        this.credentials = credentials;
        this.resolver = resolver;
        this.clock = clock;
    }
    protected abstract long identity(YooseeAccount account) throws IOException;
    protected abstract byte[] listRequest(long identity);
    protected abstract RemotePresence query(ProbeRequest request, YooseeAccount account, GuteTcpSession session,
                                            GuteFrameCodec codec) throws IOException;

    @Override public final ProbeResult check(ProbeRequest request, BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        if (request.protocol() != protocol) { throw new IllegalArgumentException("Wrong Yoosee remote backend."); }
        long started = System.nanoTime();
        var deadline = new YooseeDeadline(request.timeout(), cancelled);
        var resolved = new ArrayList<InetSocketAddress>();
        var responded = new ArrayList<InetSocketAddress>();
        Optional<InetSocketAddress> local = Optional.empty();
        ProbeResult.Status status = ProbeResult.Status.INCONCLUSIVE;
        String detail = "No remote presence was established. P2P reachability was not tested.";
        Optional<RemotePresence> presence = Optional.empty();
        boolean owned = false;
        try (var logging = LogContext.probe()) {
            LOG.info("event=yoosee_remote_started protocol={} timeout_ms={} server_count={}", protocol,
                    request.timeout().toMillis(), request.servers().size());
            try {
                deadline.check();
                awaitOwnership(deadline, progress);
                owned = true;
                deadline.check();
                var loaded = credentials.load(protocol);
                if (loaded.isEmpty()) {
                    status = ProbeResult.Status.AUTH_REQUIRED;
                    detail = "Select user-authorized " + protocol + " account credentials. No remote packets were sent.";
                } else {
                    try (var account = loaded.orElseThrow(); var codec = new GuteFrameCodec(protocol)) {
                        ensureFresh(account);
                        long identity = identity(account);
                        resolved.addAll(resolve(request.servers(), deadline.phase(request.timeout().dividedBy(4))));
                        deadline.check();
                        if (resolved.isEmpty()) { detail = "No resolved IPv4 list server. Remote presence is unknown."; }
                        else {
                            progress.accept("Discovering " + protocol + " infrastructure (not camera P2P). ");
                            var reply = YooseeListDiscovery.discover(resolved, listRequest(identity), identity, protocol, codec,
                                    deadline.phase(Duration.ofNanos(Math.min(3_000_000_000L, Math.max(1, deadline.remaining() / 3)))));
                            responded.add(reply.listServer());
                            boolean completed = false;
                            int remainingEndpoints = reply.tcpServers().size();
                            for (var endpoint : reply.tcpServers()) {
                                deadline.check();
                                ensureFresh(account);
                                var attempt = deadline.phase(Duration.ofNanos(Math.max(1, deadline.remaining() / remainingEndpoints--)));
                                RemotePresence evidence;
                                try (var transport = new YooseeTcpTransport(endpoint, attempt,
                                        protocol == TransportProtocol.GWELL ? 1500 : 10240);
                                     var sessionCodec = new GuteFrameCodec(protocol);
                                     var session = new GuteTcpSession(protocol, identity, transport, attempt, sessionCodec,
                                             () -> { if (!responded.contains(endpoint)) { responded.add(endpoint); } })) {
                                    local = Optional.of(transport.localEndpoint());
                                    progress.accept("Registering the authorized " + protocol + " client; then querying server presence.");
                                    evidence = query(request, account, session, sessionCodec);
                                    ensureFresh(account);
                                    deadline.check();
                                } catch (GuteTcpSession.AuthenticationFailed rejected) {
                                    throw rejected;
                                } catch (IOException failed) {
                                    if (attempt.cancelled()) { throw failed; }
                                    detail = "Registration/query did not yield validated remote presence. Camera reachability is unknown.";
                                    continue;
                                }
                                // Publish only after the socket, key schedules and fragment assemblies close.
                                ensureFresh(account);
                                deadline.check();
                                presence = Optional.of(evidence);
                                status = switch (evidence.state()) {
                                    case ONLINE -> ProbeResult.Status.REPORTED_ONLINE;
                                    case OFFLINE -> ProbeResult.Status.REPORTED_OFFLINE;
                                    case UNKNOWN, STALE -> ProbeResult.Status.INCONCLUSIVE;
                                };
                                detail = "Server presence: " + evidence.state() + ". Camera P2P reachability was not tested.";
                                completed = true;
                                break;
                            }
                            if (!completed && deadline.remaining() == 0) { status = ProbeResult.Status.TIMED_OUT; }
                        }
                    }
                }
            } catch (GuteTcpSession.AuthenticationFailed rejected) {
                status = ProbeResult.Status.AUTH_FAILED;
                detail = rejected.getMessage();
            } catch (SocketTimeoutException timedOut) {
                status = ProbeResult.Status.TIMED_OUT;
                detail = "Remote session deadline expired. Missing replies do not prove the camera is offline.";
            } catch (InterruptedIOException interrupted) {
                status = ProbeResult.Status.CANCELLED;
                detail = "Remote check cancelled; session resources and transient keys were closed.";
            } catch (IOException failed) {
                // Do not attach nested errors or raw packets: credentials and model content can be sensitive.
                detail = "Remote credentials, registration, or response validation failed. Presence remains unknown.";
            } finally {
                if (owned) { ownership.unlock(); }
            }
            if (deadline.cancelled()) { status = ProbeResult.Status.CANCELLED; presence = Optional.empty(); }
            var information = new P2pInformation(Optional.of(protocol), P2pInformation.ConnectionMode.NONE, local,
                    resolved, responded, List.of(), List.of(), Map.of());
            var result = new ProbeResult(status, detail, !responded.isEmpty(), Optional.empty(), Optional.empty(),
                    Duration.ofNanos(System.nanoTime() - started), information, List.of(), presence);
            LOG.info("event=yoosee_remote_completed protocol={} status={} elapsed_ms={} server_responded={}",
                    protocol, result.status(), result.elapsed().toMillis(), result.serverResponded());
            return result;
        }
    }

    private void awaitOwnership(YooseeDeadline deadline, Consumer<String> progress) throws IOException {
        try {
            if (ownership.tryLock(0, TimeUnit.NANOSECONDS)) { return; }
            progress.accept("Waiting for the owned " + protocol + " account session to close; queue time counts against this check.");
            while (!ownership.tryLock(deadline.pollMillis(), TimeUnit.MILLISECONDS)) { deadline.check(); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Yoosee check interrupted while waiting for account ownership.");
        }
    }

    private void ensureFresh(YooseeAccount account) throws GuteTcpSession.AuthenticationFailed {
        if (account.expiresAt().filter(expiry -> !clock.instant().isBefore(expiry)).isPresent()) {
            throw new GuteTcpSession.AuthenticationFailed();
        }
    }

    private List<InetSocketAddress> resolve(List<InetSocketAddress> servers, YooseeDeadline deadline) throws IOException {
        var tasks = new ArrayList<FutureTask<List<InetSocketAddress>>>();
        var result = new ArrayList<InetSocketAddress>();
        if (servers.size() > 64) { throw new IOException("Too many Yoosee list servers."); }
        for (var server : servers) {
            var task = new FutureTask<>(() -> {
                var addresses = server.isUnresolved() ? resolver.resolve(server.getHostString())
                        : new java.net.InetAddress[]{server.getAddress()};
                return Arrays.stream(addresses).filter(Inet4Address.class::isInstance).distinct()
                        .limit(256).map(address -> new InetSocketAddress(address, server.getPort())).toList();
            });
            tasks.add(task);
            Thread.ofVirtual().name("yoosee-dns").start(task);
        }
        try {
            while (tasks.stream().anyMatch(task -> !task.isDone()) && deadline.remaining() > 0) {
                deadline.check();
                for (var task : tasks) {
                    if (!task.isDone()) {
                        try { task.get(Math.max(1, Math.min(50_000_000, deadline.remaining())), TimeUnit.NANOSECONDS); }
                        catch (ExecutionException | TimeoutException ignored) { /* Collect settled results below. */ }
                    }
                }
            }
            for (var task : tasks) {
                if (task.isDone()) {
                    try {
                        for (var endpoint : task.get()) {
                            if (!result.contains(endpoint) && result.size() < 256) { result.add(endpoint); }
                        }
                    } catch (ExecutionException failed) { /* A failed host does not discard other IPv4 addresses. */ }
                }
            }
            return List.copyOf(result);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Yoosee check interrupted during resolution.");
        } finally { tasks.forEach(task -> task.cancel(true)); }
    }
}
