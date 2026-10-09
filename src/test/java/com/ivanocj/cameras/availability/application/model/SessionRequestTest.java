package com.ivanocj.cameras.availability.application.model;

import com.ivanocj.cameras.availability.testsupport.DeviceFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionRequestTest {
    @Test
    void hostsThatResolveToTheSameEndpointBecomeOneServer() {
        var server = new InetSocketAddress("127.0.0.1", 32100);
        assertEquals(List.of(server), new SessionRequest(DeviceFixtures.DID, List.of(server, server),
                Duration.ofSeconds(1), false).servers());
    }

    @Test
    void acceptsOnlyResolvedServerAddresses() {
        assertThrows(IllegalArgumentException.class, () -> new SessionRequest(DeviceFixtures.DID,
                List.of(InetSocketAddress.createUnresolved("server.example", 32100)), Duration.ofSeconds(1), false));
    }

    @Test
    void aLanOnlySessionMayHaveNoServers() {
        assertEquals(List.of(), new SessionRequest(DeviceFixtures.DID, List.of(), Duration.ofSeconds(1), true).servers());
    }

    @Test
    void needsAtLeastOneServer() {
        assertThrows(IllegalArgumentException.class, () -> new SessionRequest(DeviceFixtures.DID,
                List.of(), Duration.ofSeconds(1), false));
    }
}
