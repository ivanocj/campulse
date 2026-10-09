package com.ivanocj.cameras.availability.application.service;

import com.ivanocj.cameras.availability.application.model.GwellLanDevice;
import com.ivanocj.cameras.availability.application.model.LanDiscoveryResult;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import com.ivanocj.cameras.availability.domain.model.IotVideoDeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.probe.GwellRemoteProbe;
import com.ivanocj.cameras.availability.infrastructure.probe.IotVideoRemoteProbe;
import java.time.Clock;
import com.ivanocj.cameras.availability.testsupport.DeviceFixtures;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YooseeAvailabilityServiceTest {
    @Test
    void protocolRoutingDoesNotSendYooseeRequestsThroughTheLegacyProbe() throws Exception {
        var called = new AtomicReference<String>();
        var router = new ProtocolProbeRouter((request, cancel, progress) -> {
            called.set("legacy"); return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
        }, (request, cancel, progress) -> {
            called.set("gwell"); return new ProbeResult(ProbeResult.Status.DISCOVERED_LAN);
        }, (request, cancel, progress) -> {
            called.set("iotvideo"); return new ProbeResult(ProbeResult.Status.UNSUPPORTED);
        });
        router.check(gwell(true), () -> false, message -> {});
        assertEquals("gwell", called.get());
        router.check(iotvideo(), () -> false, message -> {});
        assertEquals("iotvideo", called.get());
        router.check(new ProbeRequest(DeviceFixtures.DID, List.of(DeviceFixtures.SERVER), Duration.ofSeconds(1), false),
                () -> false, message -> {});
        assertEquals("legacy", called.get());
    }

    @Test
    void aMatchingLanReplyIsDiscoveryEvidenceNotAnAliveConfirmedSession() throws Exception {
        var zeroNamespace = device("01234");
        var plainNamespace = device("1234");
        var service = new GwellAvailabilityService((timeout, cancelled, progress) -> new LanDiscoveryResult(
                List.of(plainNamespace, zeroNamespace), false, Duration.ofSeconds(1), Optional.empty()),
                remoteGwell());
        var result = service.check(gwell(true), () -> false, message -> {});
        assertEquals(ProbeResult.Status.DISCOVERED_LAN, result.status());
        assertFalse(result.serverResponded());
        assertTrue(result.peer().isEmpty());
        assertTrue(result.publicEndpoint().isEmpty());
        assertEquals(List.of(zeroNamespace), result.lanDevices());
        assertEquals(List.of(zeroNamespace.endpoint()), result.p2pInformation().deviceCandidates());
        assertEquals(Optional.of(TransportProtocol.GWELL), result.p2pInformation().protocol());
        assertEquals(Duration.ofSeconds(1), result.elapsed());
    }

    @Test
    void aDifferentNamespaceOrNoLanReplyCannotBeLabelledOfflineOrReachable() throws Exception {
        var service = new GwellAvailabilityService((timeout, cancelled, progress) -> new LanDiscoveryResult(
                List.of(device("1234")), false, Duration.ofSeconds(1), Optional.empty()), remoteGwell());
        var result = service.check(gwell(true), () -> false, message -> {});
        assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
        assertTrue(result.lanDevices().isEmpty());
        assertTrue(result.detail().contains("cannot establish"));
    }

    @Test
    void cancellationKeepsPartialDiscoveryButNeverReturnsASuccessStatus() throws Exception {
        var service = new GwellAvailabilityService((timeout, cancelled, progress) -> new LanDiscoveryResult(
                List.of(device("01234")), true, Duration.ofMillis(200), Optional.empty()), remoteGwell());
        var result = service.check(gwell(true), () -> false, message -> {});
        assertEquals(ProbeResult.Status.CANCELLED, result.status());
        assertEquals(1, result.lanDevices().size());
    }

    @Test
    void remoteOnlyChecksRequireBackendSpecificCredentialsBeforeOpeningAnySocket() throws Exception {
        var service = new GwellAvailabilityService((timeout, cancel, progress) -> {
            throw new AssertionError("LAN search was disabled");
        }, remoteGwell());
        var gwell = service.check(gwell(false), () -> false, message -> {});
        assertEquals(ProbeResult.Status.AUTH_REQUIRED, gwell.status());
        assertTrue(gwell.detail().contains("No remote packets"));
        var remoteIot = new IotVideoRemoteProbe(protocol -> Optional.empty(), host -> { throw new AssertionError("No DNS before credentials"); }, Clock.systemUTC());
        var iotvideo = remoteIot.check(iotvideo(), () -> false, message -> {});
        assertEquals(ProbeResult.Status.AUTH_REQUIRED, iotvideo.status());
        assertEquals(ProbeResult.Status.CANCELLED,
                remoteIot.check(iotvideo(), () -> true, message -> {}).status());
    }

    @Test
    void failedLanListenerDoesNotPreventAnIndependentRemoteCheck() throws Exception {
        var service = new GwellAvailabilityService((timeout, cancelled, progress) -> {
            throw new java.io.IOException("Listener already owned by another program");
        }, remoteGwell());
        var result = service.check(remoteAndLan(), () -> false, ignored -> { });
        assertEquals(ProbeResult.Status.AUTH_REQUIRED, result.status());
        assertTrue(result.lanDevices().isEmpty());
    }

    @Test
    void lanMetadataDoesNotHideRemoteAuthenticationFailure() throws Exception {
        var remoteInfo = new com.ivanocj.cameras.availability.application.model.P2pInformation(Optional.of(TransportProtocol.GWELL),
                com.ivanocj.cameras.availability.application.model.P2pInformation.ConnectionMode.NONE, Optional.empty(),
                List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        var service = new GwellAvailabilityService((timeout, cancelled, progress) -> new LanDiscoveryResult(
                List.of(device("01234")), false, Duration.ofMillis(10), Optional.empty()), (request, cancelled, progress) ->
                new ProbeResult(ProbeResult.Status.AUTH_FAILED, "Account rejected", true, Optional.empty(), Optional.empty(),
                        Duration.ofMillis(20), remoteInfo));
        var result = service.check(remoteAndLan(), () -> false, ignored -> { });
        assertEquals(ProbeResult.Status.AUTH_FAILED, result.status());
        assertEquals(1, result.lanDevices().size());
        assertTrue(result.remotePresence().isEmpty());
    }

    private static GwellLanDevice device(String id) {
        return new GwellLanDevice(new GwellDeviceId(id), new InetSocketAddress("192.168.1.8", 8899),
                7, 3, 1 << 6, Optional.empty(), OptionalInt.empty());
    }

    private static GwellRemoteProbe remoteGwell() {
        return new GwellRemoteProbe(protocol -> Optional.empty(), host -> { throw new AssertionError("No DNS before credentials"); }, Clock.systemUTC());
    }

    private static ProbeRequest gwell(boolean lan) {
        return new ProbeRequest(new GwellDeviceId("01234"), List.of(), Duration.ofSeconds(1), lan, TransportProtocol.GWELL);
    }

    private static ProbeRequest remoteAndLan() {
        return new ProbeRequest(new GwellDeviceId("01234"), List.of(DeviceFixtures.SERVER), Duration.ofSeconds(1), true, TransportProtocol.GWELL);
    }

    private static ProbeRequest iotvideo() {
        return new ProbeRequest(new IotVideoDeviceId("Ab_01234"), List.of(), Duration.ofSeconds(1), false, TransportProtocol.IOTVIDEO);
    }
}
