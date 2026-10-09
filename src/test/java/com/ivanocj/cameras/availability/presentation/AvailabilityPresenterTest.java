package com.ivanocj.cameras.availability.presentation;

import com.ivanocj.cameras.availability.application.model.BatchEntry;
import com.ivanocj.cameras.availability.application.model.BatchResult;
import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter.FormInput;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter.Tone;
import com.ivanocj.cameras.availability.testsupport.DeviceFixtures;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AvailabilityPresenterTest {
    private final AvailabilityPresenter presenter = new AvailabilityPresenter(new TestServerConfigurationProvider());

    @Test
    void anEncodedConfigurationOverridesTheSelectedTransport() {
        assertEquals(TransportProtocol.PPCS, presenter.request(new FormInput(DeviceFixtures.DID.value(),
                TestServerConfigurationProvider.SY_ENCODED, 10, true, TransportProtocol.XQ)).protocol());
        assertEquals(TransportProtocol.XQ, presenter.request(new FormInput(DeviceFixtures.DID.value(),
                DeviceFixtures.XQ_FFFF_CONFIGURATION, 10, true, TransportProtocol.PPCS)).protocol());
    }

    @Test
    void aPlainHostListUsesTheSelectedTransport() {
        var request = presenter.request(new FormInput(DeviceFixtures.DID.value(), "127.0.0.1", 7, false,
                TransportProtocol.XQ));
        assertEquals(TransportProtocol.XQ, request.protocol());
        assertEquals(Duration.ofSeconds(7), request.timeout());
    }

    @Test
    void identifierHelpDescribesEachFamilysExistingInputContract() {
        for (var protocol : List.of(TransportProtocol.PPCS, TransportProtocol.XQ)) {
            assertTrue(AvailabilityPresenter.identifierHelp(protocol).contains("PREFIX-SERIAL-CODE"));
        }
        assertTrue(AvailabilityPresenter.identifierHelp(TransportProtocol.GWELL).contains("leading zero"));
        assertTrue(AvailabilityPresenter.identifierHelp(TransportProtocol.IOTVIDEO).contains("case matters"));
    }

    @Test
    void singleContextUsesTheSubmittedProtocolAndPreservesItsIdentifier() {
        var identifiers = Map.of(TransportProtocol.PPCS, "WWWW-000001-A", TransportProtocol.XQ, "FFFF-000001-A",
                TransportProtocol.GWELL, "001234", TransportProtocol.IOTVIDEO, "Ab_01234");
        for (var entry : identifiers.entrySet()) {
            var request = new ProbeRequest(entry.getKey().parseIdentifier(entry.getValue()), List.of(),
                    Duration.ofSeconds(10), true, entry.getKey());
            assertEquals("Single check · " + entry.getKey() + "\n" + entry.getValue(),
                    AvailabilityPresenter.singleContext(request));
        }
    }

    @Test
    void batchRowContextDistinguishesRepeatedIdentifiersAndKeepsMultilineDescriptions() {
        var first = new BatchEntry(2, "GWELL:001234", "Câmera entrada\nNorth door", Optional.empty(), "Not tested.");
        var second = new BatchEntry(5, "GWELL:001234", "Second camera", Optional.empty(), "Not tested.");
        assertEquals("Batch summary · devices.csv", AvailabilityPresenter.batchContext("devices.csv"));
        assertEquals("Batch row · devices.csv · line 2\nGWELL:001234\nCâmera entrada\nNorth door",
                AvailabilityPresenter.batchRowContext("devices.csv", first));
        assertEquals("Batch row · devices.csv · line 5\nGWELL:001234\nSecond camera",
                AvailabilityPresenter.batchRowContext("devices.csv", second));
    }

    @Test
    void skippedEntriesWithoutDescriptionsStillHaveAnUnambiguousOwner() {
        var skipped = new BatchEntry(7, "broken", Optional.empty(), "Invalid identifier.");
        assertEquals("Batch row · untested.txt · line 7\nbroken",
                AvailabilityPresenter.batchRowContext("untested.txt", skipped));
    }

    @Test
    void cancellationAcknowledgmentIsProgressNotACompletedOutcomeOrNetworkEvidence() {
        var view = AvailabilityPresenter.cancelling();
        assertEquals("Cancellation requested", view.headline());
        assertEquals(Tone.PROGRESS, view.tone());
        assertTrue(view.detail().contains("cleanup"));
        assertEquals("Not yet complete", view.serverHealth());
        assertEquals(List.of("—", "—", "—", "—", ""),
                List.of(view.peer(), view.publicEndpoint(), view.elapsed(), view.deviceEndpoint(), view.p2pInformation()));
    }

    @Test
    void everyOutcomeHasReadableWordingAndATone() {
        var expected = new java.util.HashMap<>(Map.of(
                ProbeResult.Status.REACHABLE_P2P, "Reachable · direct P2P",
                ProbeResult.Status.REACHABLE_RELAY, "Reachable · UDP relay",
                ProbeResult.Status.TIMED_OUT, "Not confirmed · timed out",
                ProbeResult.Status.SERVER_REJECTED, "Rejected by server",
                ProbeResult.Status.INCONCLUSIVE, "Inconclusive",
                ProbeResult.Status.CANCELLED, "Cancelled",
                ProbeResult.Status.DISCOVERED_LAN, "Discovered on LAN",
                ProbeResult.Status.UNSUPPORTED, "Unsupported backend"));
        expected.put(ProbeResult.Status.REPORTED_ONLINE, "Reported online · P2P not tested");
        expected.put(ProbeResult.Status.REPORTED_OFFLINE, "Reported offline · server presence");
        expected.put(ProbeResult.Status.AUTH_REQUIRED, "Account credentials required");
        expected.put(ProbeResult.Status.AUTH_FAILED, "Account authentication failed");
        for (var status : ProbeResult.Status.values()) {
            assertEquals(expected.get(status), AvailabilityPresenter.completed(new ProbeResult(status)).headline());
        }
        assertEquals(Tone.POSITIVE, AvailabilityPresenter.completed(new ProbeResult(ProbeResult.Status.REACHABLE_P2P)).tone());
        assertEquals(Tone.NEGATIVE, AvailabilityPresenter.completed(new ProbeResult(ProbeResult.Status.SERVER_REJECTED)).tone());
    }

    @Test
    void aFailureWithoutAMessageStillExplainsItself() {
        assertEquals("IOException", AvailabilityPresenter.failed(new IOException()).detail());
    }

    @Test
    void invalidInputClearsPreviousResultMetrics() {
        var view = AvailabilityPresenter.invalid("Enter a DID.");
        assertEquals(new AvailabilityPresenter.ResultView("Invalid input", Tone.CAUTION, "Enter a DID.", "Not checked",
                "—", "—", "—"), view);
    }

    @Test
    void buildsAHiChipDidAndExplainsInvalidSerials() {
        assertEquals("FFFF-158409-EBDAC", AvailabilityPresenter.hichipDid(" ffff ", "158409"));
        var error = assertThrows(IllegalArgumentException.class, () -> AvailabilityPresenter.hichipDid("FFFF", "abc"));
        assertTrue(error.getMessage().startsWith("Enter a valid HiChip prefix"));
    }

    @Test
    void aBatchSummaryCountsOnlyPeerConfirmedCodesAsReachable() {
        var results = List.of(checked(ProbeResult.Status.REACHABLE_P2P), checked(ProbeResult.Status.REACHABLE_RELAY),
                checked(ProbeResult.Status.TIMED_OUT), checked(ProbeResult.Status.INCONCLUSIVE),
                checked(ProbeResult.Status.SERVER_REJECTED),
                new BatchResult(entry(), BatchResult.State.SKIPPED, Optional.empty(), "Unknown prefix."),
                new BatchResult(entry(), BatchResult.State.FAILED, Optional.empty(), "Socket error."));
        var summary = AvailabilityPresenter.batchCompleted(results, Duration.ofMillis(2600));
        assertEquals("Batch complete", summary.headline());
        assertEquals("7 entries · 5 checked · 2 reachable · 1 skipped · 1 failed.", summary.detail());
        assertEquals("2600 ms", summary.elapsed(), "Batch elapsed time also includes failed checks and file loading");
        assertEquals(Tone.CAUTION, summary.tone());
        assertEquals("See batch results", summary.serverHealth());
    }

    @Test
    void aCancelledBatchSeparatesCompletedCodesFromUnfinishedCodes() {
        var results = List.of(checked(ProbeResult.Status.TIMED_OUT),
                new BatchResult(entry(), BatchResult.State.CANCELLED,
                        Optional.of(new ProbeResult(ProbeResult.Status.CANCELLED)), "Cancelled during the probe."),
                new BatchResult(entry(), BatchResult.State.CANCELLED, Optional.empty(), "Not tested."));
        var summary = AvailabilityPresenter.batchCompleted(results, Duration.ofSeconds(1));
        assertEquals("Batch cancelled", summary.headline());
        assertEquals("3 entries · 1 checked · 0 reachable · 0 skipped · 0 failed · 2 cancelled.", summary.detail());
        assertEquals(Tone.NEUTRAL, summary.tone());
    }

    @Test
    void batchRowsRetainTheExistingReachabilityWording() {
        for (var status : ProbeResult.Status.values()) {
            var result = checked(status);
            assertEquals(AvailabilityPresenter.completed(result.probeResult().orElseThrow()).headline(),
                    AvailabilityPresenter.batchOutcome(result));
            var individual = AvailabilityPresenter.completed(result.probeResult().orElseThrow());
            var selected = AvailabilityPresenter.batchResult(result);
            assertEquals(individual.detail(), selected.detail());
            assertEquals(individual.peer(), selected.peer());
            assertTrue(selected.p2pInformation().startsWith("Device DID: FFFF-1-A\n"));
        }
        var failed = new BatchResult(entry(), BatchResult.State.FAILED, Optional.empty(), "Socket error.");
        assertEquals("Check failed", AvailabilityPresenter.batchOutcome(failed));
        assertEquals("Socket error.", AvailabilityPresenter.batchResult(failed).detail());
        assertEquals("Not confirmed", AvailabilityPresenter.batchResult(failed).serverHealth());
    }

    @Test
    void importingAndProgressStatesDoNotClaimNetworkEvidence() {
        assertEquals("Not checked", AvailabilityPresenter.batchLoading().serverHealth());
        assertEquals("2 / 5 entries processed. See batch results below.",
                AvailabilityPresenter.batchChecking(2, 5).detail());
        assertEquals("Batch cancelled", AvailabilityPresenter.batchCancelled("No codes were tested.").headline());
        assertEquals("Not checked", AvailabilityPresenter.batchCancelled("No codes were tested.").serverHealth());
    }

    @Test
    void showsServerReturnedDeviceIpsWithoutClaimingThatTheHandshakeSucceeded() {
        var device = new InetSocketAddress("198.51.100.8", 32108);
        var second = new InetSocketAddress("198.51.100.9", 32109);
        var relay = new InetSocketAddress("192.0.2.8", 57000);
        var information = new P2pInformation(Optional.of(TransportProtocol.XQ), P2pInformation.ConnectionMode.NONE,
                Optional.of(new InetSocketAddress("192.0.2.50", 40000)), List.of(DeviceFixtures.SERVER),
                List.of(DeviceFixtures.SERVER), List.of(device, second), List.of(relay), Map.of(DeviceFixtures.SERVER, -4));
        var result = new ProbeResult(ProbeResult.Status.TIMED_OUT, "Handshake timed out.", true,
                Optional.empty(), Optional.of(new InetSocketAddress("203.0.113.4", 55000)), Duration.ofSeconds(1), information);
        var view = AvailabilityPresenter.completed(result);
        assertEquals("198.51.100.8:32108, 198.51.100.9:32109", view.deviceEndpoint());
        assertEquals("—", view.peer());
        assertEquals("Not established", AvailabilityPresenter.connectionMode(result));
        assertEquals("Not confirmed · timed out", view.headline());
        assertTrue(view.p2pInformation().contains("Protocol: XQ"));
        assertTrue(view.p2pInformation().contains("Peer confirmation: Not confirmed"));
        assertTrue(view.p2pInformation().contains("Client local endpoint: 192.0.2.50:40000"));
        assertTrue(view.p2pInformation().contains("Client public endpoint (not the device): 203.0.113.4:55000"));
        assertTrue(view.p2pInformation().contains("Responding servers: 127.0.0.1:32100"));
        assertTrue(view.p2pInformation().contains("Relay endpoints (not device IPs): 192.0.2.8:57000"));
        assertTrue(view.p2pInformation().contains("signed -4, unsigned 252 (0xFC)"));
    }

    @Test
    void neverUsesARelayIpAsTheDeviceIpOrTheClientsPublicIp() {
        var relay = new InetSocketAddress("192.0.2.8", 57000);
        var result = new ProbeResult(ProbeResult.Status.REACHABLE_RELAY, "Relay confirmed.", true,
                Optional.of(relay), Optional.of(new InetSocketAddress("203.0.113.4", 55000)), Duration.ofMillis(400),
                new P2pInformation(Optional.of(TransportProtocol.PPCS), P2pInformation.ConnectionMode.UDP_RELAY,
                        Optional.empty(), List.of(), List.of(), List.of(), List.of(relay), Map.of()));
        var view = AvailabilityPresenter.completed(result);
        assertEquals("Not returned", view.deviceEndpoint());
        assertEquals("192.0.2.8:57000", view.peer());
        assertEquals("UDP relay", AvailabilityPresenter.connectionMode(result));
        assertTrue(view.p2pInformation().contains("Peer confirmation: Alive acknowledged"));
        assertTrue(view.p2pInformation().contains("The device IP was not returned"));
        assertTrue(view.p2pInformation().contains("Relay reachability does not reveal the device IP"));
    }

    @Test
    void aRelaySessionCanStillShowADirectAddressReturnedEarlierWithoutSubstitutingTheRelay() {
        var device = new InetSocketAddress("198.51.100.8", 32108);
        var relay = new InetSocketAddress("192.0.2.8", 57000);
        var result = new ProbeResult(ProbeResult.Status.REACHABLE_RELAY, "Relay confirmed.", true,
                Optional.of(relay), Optional.empty(), Duration.ZERO,
                new P2pInformation(Optional.of(TransportProtocol.XQ), P2pInformation.ConnectionMode.UDP_RELAY,
                        Optional.empty(), List.of(), List.of(), List.of(device), List.of(relay), Map.of()));
        assertEquals("198.51.100.8:32108", AvailabilityPresenter.completed(result).deviceEndpoint());
        assertEquals("192.0.2.8:57000", AvailabilityPresenter.completed(result).peer());
    }

    @Test
    void distinguishesLanModeAndPartialPeerReadinessFromAliveConfirmation() {
        var information = new P2pInformation(Optional.of(TransportProtocol.PPCS), P2pInformation.ConnectionMode.LAN,
                Optional.empty(), List.of(), List.of(), List.of(DeviceFixtures.PEER), List.of(), Map.of());
        var result = new ProbeResult(ProbeResult.Status.TIMED_OUT, "Alive timed out.", false,
                Optional.of(DeviceFixtures.PEER), Optional.empty(), Duration.ofSeconds(1), information);
        assertEquals("LAN · not confirmed", AvailabilityPresenter.connectionMode(result));
        var view = AvailabilityPresenter.completed(result);
        assertEquals("127.0.0.1:32108", view.deviceEndpoint());
        assertTrue(view.p2pInformation().contains("Peer confirmation: DID matched; alive not confirmed"));
        assertFalse(view.p2pInformation().contains("Alive acknowledged"));
    }

    @Test
    void existingDirectResultsCanStillDisplayTheirDeviceEndpointWithoutNewMetadata() {
        var result = new ProbeResult(ProbeResult.Status.REACHABLE_P2P, "Confirmed.", false,
                Optional.of(DeviceFixtures.PEER), Optional.empty(), Duration.ZERO);
        assertEquals("127.0.0.1:32108", AvailabilityPresenter.completed(result).deviceEndpoint());
        assertEquals("Direct P2P", AvailabilityPresenter.connectionMode(result));
        assertEquals("camera.example:32108", AvailabilityPresenter.endpoint(
                InetSocketAddress.createUnresolved("camera.example", 32108)), "Formatting must never perform DNS");
    }

    @Test
    void nonProbeViewsClearP2pInformationInsteadOfReusingPreviousAddresses() {
        var views = List.of(AvailabilityPresenter.idle(), AvailabilityPresenter.checking(), AvailabilityPresenter.cancelling(),
                AvailabilityPresenter.invalid("Invalid DID"), AvailabilityPresenter.failed(new IOException()),
                AvailabilityPresenter.batchLoading(), AvailabilityPresenter.batchChecking(1, 2),
                AvailabilityPresenter.batchCompleted(List.of(checked(ProbeResult.Status.TIMED_OUT)), Duration.ZERO),
                AvailabilityPresenter.batchResult(new BatchResult(entry(), BatchResult.State.SKIPPED, Optional.empty(), "Skipped")));
        for (var view : views) {
            assertEquals("—", view.deviceEndpoint());
            assertEquals("", view.p2pInformation());
        }
    }

    private static BatchResult checked(ProbeResult.Status status) {
        return new BatchResult(entry(), status == ProbeResult.Status.CANCELLED
                ? BatchResult.State.CANCELLED : BatchResult.State.CHECKED, Optional.of(new ProbeResult(status)), "");
    }

    private static BatchEntry entry() {
        return new BatchEntry(1, "FFFF-1-A", Optional.of(
                TestServerConfigurationProvider.profileNamed("HIP2P · XQ FFFF/HHHH/GGGG")), "");
    }
}
