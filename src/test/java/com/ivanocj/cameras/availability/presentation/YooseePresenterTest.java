package com.ivanocj.cameras.availability.presentation;

import com.ivanocj.cameras.availability.application.model.BatchEntry;
import com.ivanocj.cameras.availability.application.model.BatchResult;
import com.ivanocj.cameras.availability.application.model.GwellLanDevice;
import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.RemotePresence;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import com.ivanocj.cameras.availability.domain.model.IotVideoDeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class YooseePresenterTest {
    private final AvailabilityPresenter presenter = new AvailabilityPresenter(new TestServerConfigurationProvider());

    @Test
    void blankServersAreValidForIndependentLanChecksButNotForLegacyRemoteOnlyChecks() {
        var request = presenter.request(new AvailabilityPresenter.FormInput("FFFF-1-A", "", 1, true, TransportProtocol.PPCS));
        assertTrue(request.servers().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> presenter.request(new AvailabilityPresenter.FormInput(
                "FFFF-1-A", "", 1, false, TransportProtocol.PPCS)));
    }

    @Test
    void selectedYooseeFamiliesParseTheirOwnIdentifiersWithoutCompositeDidRules() {
        assertEquals(new GwellDeviceId("01234"), presenter.request(new AvailabilityPresenter.FormInput(
                "01234", "", 1, true, TransportProtocol.GWELL)).identifier());
        assertEquals(new IotVideoDeviceId("Ab_01234"), presenter.request(new AvailabilityPresenter.FormInput(
                "Ab_01234", "", 1, false, TransportProtocol.IOTVIDEO)).identifier());
        assertTrue(AvailabilityPresenter.transportDescription(TransportProtocol.GWELL).contains("8899"));
        assertTrue(AvailabilityPresenter.transportDescription(TransportProtocol.IOTVIDEO).contains("P2P not tested"));
        assertThrows(IllegalArgumentException.class, () -> presenter.request(new AvailabilityPresenter.FormInput(
                "01234", TestServerConfigurationProvider.SY_ENCODED, 1, true, TransportProtocol.GWELL)));
    }

    @Test
    void lanMetadataIsVisibleWithoutInventingAnAlivePeerRemoteServerOrClientPublicIp() {
        var endpoint = new InetSocketAddress("192.168.1.8", 8899);
        var device = new GwellLanDevice(new GwellDeviceId("01234"), endpoint, 7, 3, 1,
                Optional.of("00:11:22:33:44:55"), OptionalInt.of(42));
        var result = new ProbeResult(ProbeResult.Status.DISCOVERED_LAN, "LAN only.", false, Optional.empty(), Optional.empty(),
                Duration.ofSeconds(1), new P2pInformation(Optional.of(TransportProtocol.GWELL), P2pInformation.ConnectionMode.NONE,
                Optional.empty(), List.of(), List.of(), List.of(endpoint), List.of(), Map.of()), List.of(device));
        var view = AvailabilityPresenter.completed(result);
        assertEquals("Discovered on LAN", view.headline());
        assertEquals(AvailabilityPresenter.Tone.CAUTION, view.tone());
        assertEquals("Not checked · LAN only", view.serverHealth());
        assertEquals("192.168.1.8:8899", view.deviceEndpoint());
        assertEquals("—", view.peer());
        assertEquals("—", view.publicEndpoint());
        assertEquals("LAN discovery · no P2P session", AvailabilityPresenter.connectionMode(result));
        assertTrue(view.p2pInformation().contains("01234"));
        assertTrue(view.p2pInformation().contains("type=7"));
        assertTrue(view.p2pInformation().contains("subtype=3"));
        assertTrue(view.p2pInformation().contains("model=42"));
        assertTrue(view.p2pInformation().contains("00:11:22:33:44:55"));
        assertFalse(view.p2pInformation().contains("Alive acknowledged"));
        var row = new BatchResult(new BatchEntry(1, "GWELL:01234", Optional.empty(), ""),
                BatchResult.State.CHECKED, Optional.of(result), "LAN only.");
        assertTrue(AvailabilityPresenter.batchCompleted(List.of(row), Duration.ofSeconds(1)).detail().contains("0 reachable"));
        assertTrue(AvailabilityPresenter.batchResult(row).p2pInformation().startsWith("Device identifier: GWELL:01234\n"));
    }

    @ParameterizedTest
    @EnumSource(value = TransportProtocol.class, names = {"PPCS", "XQ"})
    void legacyLanOnlySessionsDoNotLabelUnqueriedServersAsNonresponding(TransportProtocol protocol) {
        var endpoint = new InetSocketAddress("127.0.0.1", 40000);
        var result = new ProbeResult(ProbeResult.Status.INCONCLUSIVE, "No LAN reply.", false, Optional.empty(), Optional.empty(),
                Duration.ofSeconds(1), new P2pInformation(Optional.of(protocol), P2pInformation.ConnectionMode.NONE,
                        Optional.of(endpoint), List.of(), List.of(), List.of(), List.of(), Map.of()));
        assertEquals("Not checked · LAN only", AvailabilityPresenter.completed(result).serverHealth());
    }

    @ParameterizedTest
    @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void remotePresenceIncludesProvenanceButIsNeverDisplayedOrCountedAsReachable(TransportProtocol protocol) {
        for (var state : RemotePresence.State.values()) {
            var now = Instant.parse("2026-10-07T10:00:00Z");
            var status = switch (state) {
                case ONLINE -> ProbeResult.Status.REPORTED_ONLINE;
                case OFFLINE -> ProbeResult.Status.REPORTED_OFFLINE;
                default -> ProbeResult.Status.INCONCLUSIVE;
            };
            var presence = new RemotePresence(state, protocol, protocol == TransportProtocol.GWELL
                    ? RemotePresence.Source.GWELL_STATUS_QUERY : RemotePresence.Source.IOTVIDEO_MODEL,
                    protocol == TransportProtocol.GWELL ? Optional.empty() : Optional.of(now), now);
            var result = new ProbeResult(status, "Server presence only", true, Optional.empty(), Optional.empty(), Duration.ofSeconds(1),
                    new P2pInformation(Optional.of(protocol), P2pInformation.ConnectionMode.NONE, Optional.empty(), List.of(),
                            List.of(new InetSocketAddress("127.0.0.1", 51701)), List.of(), List.of(), Map.of()), List.of(), Optional.of(presence));
            var view = AvailabilityPresenter.completed(result);
            assertEquals(AvailabilityPresenter.Tone.CAUTION, view.tone());
            assertEquals("—", view.peer());
            assertEquals("—", view.publicEndpoint());
            assertEquals("Not returned", view.deviceEndpoint());
            assertTrue(view.p2pInformation().contains("Remote presence: " + state));
            assertTrue(view.p2pInformation().contains("source=" + presence.source()));
            assertTrue(view.p2pInformation().contains("received=" + now));
            assertTrue(view.p2pInformation().contains("P2P reachability: Not tested"));
            assertFalse(view.p2pInformation().contains("Alive acknowledged"));
            var row = new BatchResult(new BatchEntry(1, protocol + ":1234", Optional.empty(), ""),
                    BatchResult.State.CHECKED, Optional.of(result), "");
            assertTrue(AvailabilityPresenter.batchCompleted(List.of(row), Duration.ofSeconds(1)).detail().contains("0 reachable"));
        }
    }

    @Test void retainedLanMetadataDoesNotHideMissingCredentialsOrAnAttemptedRemoteCheck() {
        var device = new GwellLanDevice(new GwellDeviceId("01234"), new InetSocketAddress("192.168.1.8", 8899),
                7, 3, 1, Optional.empty(), OptionalInt.empty());
        var lanInfo = new P2pInformation(Optional.of(TransportProtocol.GWELL), P2pInformation.ConnectionMode.NONE,
                Optional.empty(), List.of(), List.of(), List.of(device.endpoint()), List.of(), Map.of());
        var auth = new ProbeResult(ProbeResult.Status.AUTH_REQUIRED, "Credentials required", false, Optional.empty(), Optional.empty(),
                Duration.ZERO, lanInfo, List.of(device));
        assertEquals("Not checked · account credentials", AvailabilityPresenter.completed(auth).serverHealth());
        var remoteInfo = new P2pInformation(Optional.of(TransportProtocol.GWELL), P2pInformation.ConnectionMode.NONE,
                Optional.empty(), List.of(new InetSocketAddress("127.0.0.1", 51701)), List.of(),
                List.of(device.endpoint()), List.of(), Map.of());
        var attempted = new ProbeResult(ProbeResult.Status.DISCOVERED_LAN, "No remote reply", false, Optional.empty(), Optional.empty(),
                Duration.ZERO, remoteInfo, List.of(device));
        assertEquals("No server response", AvailabilityPresenter.completed(attempted).serverHealth());
    }
}
