package com.ivanocj.cameras.availability.infrastructure.configuration;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServerConfigurationParserTest {
    @Test
    void parsesExplicitDeploymentEndpointsWithoutDoingDnsOnTheUiThread() {
        assertEquals(List.of(InetSocketAddress.createUnresolved("p2p.example", 32100),
                        InetSocketAddress.createUnresolved("127.0.0.1", 45000)),
                ServerConfigurationParser.parse(" p2p.example, 127.0.0.1:45000 ").servers());
    }

    @Test
    void decodesTheBellFirmwareDefaultUsingTheRecovered54ByteKey() {
        assertEquals(List.of(InetSocketAddress.createUnresolved("one.object-camera.com", 32100),
                        InetSocketAddress.createUnresolved("174.129.34.188", 32100),
                        InetSocketAddress.createUnresolved("four.object-camera.com", 32100)),
                ServerConfigurationParser.parse("BPGBBOEOKJMBHJNCFFDFAEEJCMMHDANKDFADBNHDBGJMLDLOCIACCJOPHMLCJPKJABMELOCMOCJKABHGIANGNPBLIPOIFPCPAIGJDCFDMMLCEFHLACBDPGNA").servers());
    }

    @Test
    void requiresAUsableServerHost() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfigurationParser.parse(" "));
    }

    @Test
    void identifiesKeyedConfigurationsAsAnUnsupportedProfile() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> ServerConfigurationParser.parse("BPGBBOEOKJMBHJNCFFDFAEEJCMMHDANK:secret"));
        assertTrue(error.getMessage().contains("Keyed/encrypted"));
    }

    @Test
    void anSyEncodedConfigurationIdentifiesThePpcsTransport() {
        assertEquals(Optional.of(TransportProtocol.PPCS), ServerConfigurationParser.parse(
                KnownServerProfilesTest.SY_WWWW_CONFIGURATION).protocol());
    }

    @Test
    void anXqEncodedConfigurationIdentifiesTheXqTransport() {
        assertEquals(Optional.of(TransportProtocol.XQ), ServerConfigurationParser.parse(XqConfigurationTest.FFFF).protocol());
    }

    @Test
    void aPlainHostListLeavesTheTransportToTheUser() {
        assertEquals(Optional.empty(), ServerConfigurationParser.parse("p2p.example, 127.0.0.1:45000").protocol());
    }

    @Test
    void acceptsXqEncodedServerListsThroughTheCommonInputParser() {
        assertEquals(XqConfiguration.parse(XqConfigurationTest.FFFF),
                ServerConfigurationParser.parse(XqConfigurationTest.FFFF));
    }
}
