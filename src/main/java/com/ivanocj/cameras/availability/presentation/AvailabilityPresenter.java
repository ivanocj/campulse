package com.ivanocj.cameras.availability.presentation;

import com.ivanocj.cameras.availability.application.model.BatchEntry;
import com.ivanocj.cameras.availability.application.model.BatchResult;
import com.ivanocj.cameras.availability.application.model.P2pInformation.ConnectionMode;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.ServerConfiguration;
import com.ivanocj.cameras.availability.application.port.ServerConfigurationProvider;
import com.ivanocj.cameras.availability.domain.model.DeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.domain.service.HichipUid;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Toolkit-independent form handling and result wording for the availability view. */
public final class AvailabilityPresenter {
    private static final String NONE = "—";
    private static final String NOT_RETURNED = "Not returned";
    private static final String LOCAL_FAILURE_GUIDANCE = "Review the selected device family/server profile and local network/socket access before retrying. "
            + "This failure does not establish whether the camera is online.";
    private static final String BATCH_INPUT_GUIDANCE = "Correct the UTF-8 CSV (Serial,Description headers) or TXT (one identifier per line), "
            + "then open it again. Opening a batch starts testing immediately.";

    private final ServerConfigurationProvider configurations;

    public AvailabilityPresenter(ServerConfigurationProvider configurations) {
        this.configurations = configurations;
    }

    public record FormInput(String did, String servers, int timeoutSeconds, boolean lanSearch,
                            TransportProtocol selectedProtocol) {
    }

    public enum Tone { NEUTRAL, PROGRESS, POSITIVE, CAUTION, NEGATIVE }

    public enum InputField { IDENTIFIER, SERVERS, HICHIP_PREFIX, HICHIP_SERIAL }

    /** Adds view field attribution to an existing validation failure, preserving its message and order. */
    public static final class InvalidInput extends IllegalArgumentException {
        private final List<InputField> fields;

        private InvalidInput(List<InputField> fields, String message, IllegalArgumentException cause) {
            super(message, cause);
            this.fields = List.copyOf(fields);
        }

        public List<InputField> fields() {
            return fields;
        }
    }

    public record ResultView(String headline, Tone tone, String detail, String serverHealth, String peer,
                             String publicEndpoint, String elapsed, String deviceEndpoint, String p2pInformation, String recovery) {
        public ResultView(String headline, Tone tone, String detail, String serverHealth, String peer,
                          String publicEndpoint, String elapsed, String deviceEndpoint, String p2pInformation) {
            this(headline, tone, detail, serverHealth, peer, publicEndpoint, elapsed, deviceEndpoint, p2pInformation, "");
        }

        public ResultView(String headline, Tone tone, String detail, String serverHealth, String peer,
                          String publicEndpoint, String elapsed) {
            this(headline, tone, detail, serverHealth, peer, publicEndpoint, elapsed, NONE, "");
        }

        private ResultView withRecovery(String guidance) {
            return new ResultView(headline, tone, detail, serverHealth, peer, publicEndpoint, elapsed,
                    deviceEndpoint, p2pInformation, guidance);
        }
    }

    /**
     * Builds a request from the form. An encoded configuration decides its own transport; a plain host list
     * uses the selected one.
     *
     * @throws IllegalArgumentException with a user-readable message for invalid input
     */
    public ProbeRequest request(FormInput input) {
        ServerConfiguration configuration;
        try {
            configuration = input.servers().isBlank() && (input.lanSearch() || !input.selectedProtocol().legacyUdp())
                    ? new ServerConfiguration(List.of()) : configurations.parse(input.servers(), input.selectedProtocol());
            if (!input.selectedProtocol().legacyUdp() && configuration.protocol().isPresent()) {
                throw new IllegalArgumentException("Encoded PPCS/XQ server configurations cannot select a Yoosee backend.");
            }
        } catch (IllegalArgumentException invalid) {
            throw new InvalidInput(List.of(InputField.SERVERS), invalid.getMessage(), invalid);
        }
        var protocol = configuration.protocol().orElse(input.selectedProtocol());
        try {
            return new ProbeRequest(protocol.parseIdentifier(input.did()), configuration.servers(),
                    Duration.ofSeconds(input.timeoutSeconds()), input.lanSearch(), protocol);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidInput(List.of(InputField.IDENTIFIER), invalid.getMessage(), invalid);
        }
    }

    /** @throws IllegalArgumentException with a user-readable message for invalid input */
    public static String hichipDid(String prefix, String serial) {
        String normalizedPrefix = prefix.strip().toUpperCase(Locale.ROOT);
        long number;
        try {
            number = Long.parseLong(serial.strip());
        } catch (IllegalArgumentException invalid) {
            throw hichipInvalid(List.of(InputField.HICHIP_SERIAL), invalid);
        }
        try {
            return new DeviceId(HichipUid.generate(normalizedPrefix, number)).value();
        } catch (IllegalArgumentException invalid) {
            // Attribute only after the original generated-DID check fails. Reuse DeviceId, not a second regex.
            var fields = new ArrayList<InputField>();
            try { new DeviceId(normalizedPrefix + "-0-A"); }
            catch (IllegalArgumentException badPrefix) { fields.add(InputField.HICHIP_PREFIX); }
            try { new DeviceId("A-" + number + "-A"); }
            catch (IllegalArgumentException badSerial) { fields.add(InputField.HICHIP_SERIAL); }
            if (fields.isEmpty()) {
                // Individually valid inputs can still exceed the combined generated-DID length limit.
                fields.addAll(List.of(InputField.HICHIP_PREFIX, InputField.HICHIP_SERIAL));
            }
            throw hichipInvalid(fields, invalid);
        }
    }

    private static InvalidInput hichipInvalid(List<InputField> fields, IllegalArgumentException invalid) {
        return new InvalidInput(fields,
                "Enter a valid HiChip prefix and a numeric unsigned 32-bit serial. " + invalid.getMessage(), invalid);
    }

    public static String transportDescription(TransportProtocol protocol) {
        return switch (protocol) {
            case PPCS -> "Legacy PPCS · IPv4 UDP · direct / LAN / UDP relay";
            case XQ -> "XQ · IPv4 UDP · direct / LAN / UDP relay";
            case GWELL -> "GWell · LAN UDP 8899 / account-backed remote presence · P2P not tested";
            case IOTVIDEO -> "IoTVideo · account-backed remote presence · P2P not tested";
        };
    }

    public static String identifierHelp(TransportProtocol protocol) {
        return switch (protocol) {
            case PPCS, XQ -> "DID format: PREFIX-SERIAL-CODE (e.g. FFFF-158409-EBDAC).";
            case GWELL -> "Numeric GWell ID; preserve its leading zero (e.g. 01234).";
            case IOTVIDEO -> "Original IoTVideo device key; case matters (e.g. Ab_01234).";
        };
    }

    public static String singleContext(ProbeRequest request) {
        return "Single check · " + request.protocol() + "\n" + request.identifier().value();
    }

    public static String batchContext(String fileName) {
        return "Batch summary · " + fileName;
    }

    public static String batchRowContext(String fileName, BatchEntry entry) {
        return "Batch row · " + fileName + " · line " + entry.lineNumber() + "\n" + entry.code()
                + (entry.description().isEmpty() ? "" : "\n" + entry.description());
    }

    public static ResultView idle() {
        return new ResultView("Ready to check", Tone.NEUTRAL, "", "Not checked", NONE, NONE, NONE);
    }

    public static ResultView checking() {
        return new ResultView("Checking…", Tone.PROGRESS, "", "Checking…", NONE, NONE, NONE);
    }

    /** Acknowledges the request, not completed cancellation or a terminal probe outcome. */
    public static ResultView cancelling() {
        return new ResultView("Cancellation requested", Tone.PROGRESS,
                "Waiting for the current operation and resource cleanup. Inputs remain locked until it completes.",
                "Not yet complete", NONE, NONE, NONE);
    }

    public static ResultView batchLoading() {
        return new ResultView("Loading batch…", Tone.PROGRESS,
                "Reading device identifiers and descriptions; routing each entry through known server profiles.",
                "Not checked", NONE, NONE, NONE);
    }

    public static ResultView batchChecking(int processed, int total) {
        return new ResultView("Batch checking…", Tone.PROGRESS,
                processed + " / " + total + " entries processed. See batch results below.",
                "See batch results", NONE, NONE, NONE);
    }

    public static ResultView batchCancelled(String detail) {
        return new ResultView("Batch cancelled", Tone.NEUTRAL, detail, "Not checked", NONE, NONE, NONE);
    }

    public static String batchOutcome(BatchResult result) {
        return switch (result.state()) {
            case CHECKED -> headline(result.probeResult().orElseThrow().status());
            case SKIPPED -> "Skipped";
            case FAILED -> "Check failed";
            case CANCELLED -> "Cancelled";
        };
    }

    public static ResultView batchResult(BatchResult result) {
        return result.probeResult().map(probe -> completed(probe,
                (probe.p2pInformation().protocol().filter(protocol -> !protocol.legacyUdp()).isPresent()
                        ? "Device identifier: " : "Device DID: ") + result.entry().code() + "\n"
                + (result.entry().description().isEmpty() ? "" : "Description: " + result.entry().description() + "\n")))
                .orElseGet(() -> new ResultView(batchOutcome(result), switch (result.state()) {
                        case FAILED -> Tone.NEGATIVE;
                        case SKIPPED -> Tone.CAUTION;
                        default -> Tone.NEUTRAL;
                    }, result.detail(), result.state() == BatchResult.State.FAILED ? "Not confirmed" : "Not checked",
                            NONE, NONE, NONE).withRecovery(result.state() == BatchResult.State.FAILED ? LOCAL_FAILURE_GUIDANCE : ""));
    }

    public static ResultView batchCompleted(List<BatchResult> results, Duration elapsed) {
        long checked = results.stream().filter(result -> result.state() == BatchResult.State.CHECKED).count();
        long skipped = results.stream().filter(result -> result.state() == BatchResult.State.SKIPPED).count();
        long failed = results.stream().filter(result -> result.state() == BatchResult.State.FAILED).count();
        long cancelled = results.stream().filter(result -> result.state() == BatchResult.State.CANCELLED).count();
        long reachable = results.stream().flatMap(result -> result.probeResult().stream())
                .filter(result -> result.status() == ProbeResult.Status.REACHABLE_P2P
                        || result.status() == ProbeResult.Status.REACHABLE_RELAY).count();
        var detail = results.size() + " entries · " + checked + " checked · " + reachable + " reachable · "
                + skipped + " skipped · " + failed + " failed"
                + (cancelled > 0 ? " · " + cancelled + " cancelled" : "") + ".";
        var tone = cancelled > 0 ? Tone.NEUTRAL
                : reachable == results.size() && reachable > 0 ? Tone.POSITIVE : Tone.CAUTION;
        return new ResultView(cancelled > 0 ? "Batch cancelled" : "Batch complete", tone, detail,
                "See batch results", NONE, NONE, elapsed.toMillis() + " ms");
    }

    public static ResultView invalid(String message) {
        return new ResultView("Invalid input", Tone.CAUTION, message, "Not checked", NONE, NONE, NONE);
    }

    public static ResultView failed(Throwable cause) {
        String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return new ResultView("Check failed", Tone.NEGATIVE, message, "Not confirmed", NONE, NONE, NONE)
                .withRecovery(LOCAL_FAILURE_GUIDANCE);
    }

    public static ResultView batchInvalid(String message) {
        return invalid(message).withRecovery(BATCH_INPUT_GUIDANCE);
    }

    public static ResultView batchFailed(Throwable cause) {
        return failed(cause).withRecovery("Check that the selected batch file is readable and review local network/socket access before retrying. "
                + "Completed rows remain available; a local failure is not proof that a camera is offline.");
    }

    public static ResultView completed(ProbeResult result) {
        return completed(result, "");
    }

    private static ResultView completed(ProbeResult result, String context) {
        return new ResultView(headline(result.status()), tone(result.status()), result.detail(),
                serverHealth(result),
                result.peer().map(AvailabilityPresenter::endpoint).orElse(NONE),
                result.publicEndpoint().map(AvailabilityPresenter::endpoint).orElse(NONE),
                result.elapsed().toMillis() + " ms", deviceEndpoints(result), context + p2pDetails(result))
                .withRecovery(recovery(result.status()));
    }

    private static String recovery(ProbeResult.Status status) {
        return switch (status) {
            case TIMED_OUT -> "Verify the device identifier, matching deployment, and network/LAN access before retrying. A timeout does not prove the device is offline.";
            case SERVER_REJECTED -> "Verify the device identifier and the server profile for its deployment. A rejection does not prove the device is powered off.";
            case INCONCLUSIVE -> "Review the available evidence and selected device family/deployment before retrying. Unknown or stale presence does not prove the device is offline.";
            case AUTH_REQUIRED -> "Open Yoosee account · single & batch and select a private file from your authorized account login provider. Selection does not validate credentials; use account credentials, not a camera password.";
            case AUTH_FAILED -> "Refresh credentials through your authorized account login provider, then select the updated private account file and retry. This is not evidence that the camera is offline.";
            case UNSUPPORTED -> "Choose a supported device family/server profile. No camera reachability has been confirmed.";
            default -> "";
        };
    }

    /** Returned device candidates, plus a selected direct/LAN peer; never substitutes a relay or client IP. */
    public static String deviceEndpoints(ProbeResult result) {
        var addresses = new LinkedHashSet<>(result.p2pInformation().deviceCandidates());
        var mode = mode(result);
        if (mode == ConnectionMode.DIRECT_P2P || mode == ConnectionMode.LAN) {
            result.peer().ifPresent(addresses::add);
        }
        return addresses.isEmpty() ? NOT_RETURNED : endpoints(addresses);
    }

    public static String connectionMode(ProbeResult result) {
        if (result.status() == ProbeResult.Status.DISCOVERED_LAN || !result.lanDevices().isEmpty()) {
            return "LAN discovery · no P2P session";
        }
        var mode = mode(result);
        var label = switch (mode) {
            case NONE -> "Not established";
            case LAN -> "LAN";
            case DIRECT_P2P -> "Direct P2P";
            case UDP_RELAY -> "UDP relay";
        };
        return label + (mode != ConnectionMode.NONE && !peerConfirmed(result) ? " · not confirmed" : "");
    }

    private static ConnectionMode mode(ProbeResult result) {
        if (result.p2pInformation().mode() != ConnectionMode.NONE) {
            return result.p2pInformation().mode();
        }
        // Preserve useful wording for callers using the original ProbeResult constructors.
        return switch (result.status()) {
            case REACHABLE_P2P -> ConnectionMode.DIRECT_P2P;
            case REACHABLE_RELAY -> ConnectionMode.UDP_RELAY;
            default -> ConnectionMode.NONE;
        };
    }

    private static boolean peerConfirmed(ProbeResult result) {
        return result.status() == ProbeResult.Status.REACHABLE_P2P || result.status() == ProbeResult.Status.REACHABLE_RELAY;
    }

    private static String p2pDetails(ProbeResult result) {
        var information = result.p2pInformation();
        if (information.protocol().filter(protocol -> !protocol.legacyUdp()).isPresent()) {
            var discoveries = result.lanDevices().stream().map(device -> "  " + device.identifier().value()
                    + " @ " + endpoint(device.endpoint()) + " · type=" + device.deviceType() + " · subtype=" + device.subType()
                    + (device.model().isPresent() ? " · model=" + device.model().getAsInt() : "")
                    + device.macAddress().map(mac -> " · MAC=" + mac).orElse("")).collect(Collectors.joining("\n"));
            return "Protocol: " + information.protocol().orElseThrow()
                    + "\nConnection mode: " + connectionMode(result)
                    + "\nP2P reachability: Not tested"
                    + "\nClient local endpoint: " + information.localEndpoint().map(AvailabilityPresenter::endpoint).orElse(NONE)
                    + "\nGWell LAN discovery replies:\n" + (discoveries.isEmpty() ? NONE : discoveries)
                    + "\n\nDiscovery replies are unauthenticated metadata, not an alive-confirmed P2P session."
                    + "\nRemote presence: " + result.remotePresence().map(presence -> presence.state()
                            + " · source=" + presence.source() + " · received=" + presence.receivedAt()
                            + presence.reportedAt().map(time -> " · reported=" + time).orElse(" · server timestamp not supplied"))
                            .orElse("Not reported")
                    + "\nResponding infrastructure: " + endpoints(information.respondingServers())
                    + "\nServer-reported presence does not confirm camera P2P reachability.";
        }
        var devices = deviceEndpoints(result);
        var confirmation = peerConfirmed(result) ? "Alive acknowledged"
                : result.peer().isPresent() ? "DID matched; alive not confirmed" : "Not confirmed";
        var statuses = information.serverStatuses().entrySet().stream().map(entry -> {
            int unsigned = entry.getValue() & 0xff;
            return "  " + endpoint(entry.getKey()) + ": signed " + entry.getValue() + ", unsigned " + unsigned
                    + String.format(Locale.ROOT, " (0x%02X)", unsigned);
        }).collect(Collectors.joining("\n"));
        return "Protocol: " + information.protocol().map(Enum::name).orElse(NONE)
                + "\nConnection mode: " + connectionMode(result)
                + "\nPeer confirmation: " + confirmation
                + "\nDevice IP / port: " + devices
                + "\nSession endpoint (device or relay): " + result.peer().map(AvailabilityPresenter::endpoint).orElse(NONE)
                + "\nClient local endpoint: " + information.localEndpoint().map(AvailabilityPresenter::endpoint).orElse(NONE)
                + "\nClient public endpoint (not the device): " + result.publicEndpoint().map(AvailabilityPresenter::endpoint).orElse(NONE)
                + "\nResolved servers: " + endpoints(information.resolvedServers())
                + "\nResponding servers: " + endpoints(information.respondingServers())
                + "\nReported/discovered device candidates: " + endpoints(information.deviceCandidates())
                + "\nRelay endpoints (not device IPs): " + endpoints(information.relayCandidates())
                + "\nDID lookup replies (last raw byte per server):\n" + (statuses.isEmpty() ? NONE : statuses)
                + "\n\nReported/discovered addresses alone do not confirm device reachability."
                + (devices.equals(NOT_RETURNED) ? "\nThe device IP was not returned by this exchange." : "")
                + (mode(result) == ConnectionMode.UDP_RELAY
                        ? "\nRelay reachability does not reveal the device IP unless a direct address was also returned." : "");
    }

    private static String endpoints(Collection<InetSocketAddress> addresses) {
        return addresses.isEmpty() ? NONE : addresses.stream().map(AvailabilityPresenter::endpoint)
                .collect(Collectors.joining(", "));
    }

    private static String serverHealth(ProbeResult result) {
        if (result.serverResponded()) {
            return "Server responded";
        }
        if (result.status() == ProbeResult.Status.UNSUPPORTED) {
            return "Not checked · unsupported backend";
        }
        if (result.status() == ProbeResult.Status.AUTH_REQUIRED || result.status() == ProbeResult.Status.AUTH_FAILED) {
            return "Not checked · account credentials";
        }
        var information = result.p2pInformation();
        if (information.resolvedServers().isEmpty() && (result.status() == ProbeResult.Status.DISCOVERED_LAN
                || !result.lanDevices().isEmpty() || (information.protocol().isPresent() && information.localEndpoint().isPresent()))) {
            return "Not checked · LAN only";
        }
        return "No server response";
    }

    private static String headline(ProbeResult.Status status) {
        return switch (status) {
            case REACHABLE_P2P -> "Reachable · direct P2P";
            case REACHABLE_RELAY -> "Reachable · UDP relay";
            case TIMED_OUT -> "Not confirmed · timed out";
            case SERVER_REJECTED -> "Rejected by server";
            case INCONCLUSIVE -> "Inconclusive";
            case CANCELLED -> "Cancelled";
            case DISCOVERED_LAN -> "Discovered on LAN";
            case UNSUPPORTED -> "Unsupported backend";
            case REPORTED_ONLINE -> "Reported online · P2P not tested";
            case REPORTED_OFFLINE -> "Reported offline · server presence";
            case AUTH_REQUIRED -> "Account credentials required";
            case AUTH_FAILED -> "Account authentication failed";
        };
    }

    private static Tone tone(ProbeResult.Status status) {
        return switch (status) {
            case REACHABLE_P2P, REACHABLE_RELAY -> Tone.POSITIVE;
            case TIMED_OUT, INCONCLUSIVE, DISCOVERED_LAN, UNSUPPORTED, REPORTED_ONLINE, REPORTED_OFFLINE, AUTH_REQUIRED -> Tone.CAUTION;
            case SERVER_REJECTED, AUTH_FAILED -> Tone.NEGATIVE;
            case CANCELLED -> Tone.NEUTRAL;
        };
    }

    /** Formats addresses without DNS or reverse lookups on the UI thread. */
    public static String endpoint(InetSocketAddress endpoint) {
        var host = endpoint.isUnresolved() ? endpoint.getHostString() : endpoint.getAddress().getHostAddress();
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + endpoint.getPort();
    }
}
