package com.ivanocj.cameras.availability.application.model;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class P2pInformationTest {
    @Test
    void snapshotsNetworkInformationIntoImmutableCollections() {
        var address = new InetSocketAddress("127.0.0.1", 32100);
        var addresses = new ArrayList<>(List.of(address));
        var statuses = new LinkedHashMap<InetSocketAddress, Integer>();
        statuses.put(address, -4);
        var information = new P2pInformation(Optional.of(TransportProtocol.XQ),
                P2pInformation.ConnectionMode.DIRECT_P2P, Optional.of(address), addresses, addresses,
                addresses, addresses, statuses);
        addresses.clear();
        statuses.clear();
        assertEquals(List.of(address), information.deviceCandidates());
        assertEquals(-4, information.serverStatuses().get(address));
        assertThrows(UnsupportedOperationException.class, () -> information.respondingServers().clear());
        assertThrows(UnsupportedOperationException.class, () -> information.serverStatuses().clear());
    }

    @Test
    void existingResultConstructorsStillRepresentMissingNetworkMetadataHonestly() {
        var result = new ProbeResult(ProbeResult.Status.INCONCLUSIVE, "No reply", false,
                Optional.empty(), Optional.empty(), Duration.ofSeconds(1));
        assertEquals(P2pInformation.empty(), result.p2pInformation());
        assertEquals(Optional.empty(), result.p2pInformation().protocol());
        assertEquals(P2pInformation.ConnectionMode.NONE, result.p2pInformation().mode());
    }
}
