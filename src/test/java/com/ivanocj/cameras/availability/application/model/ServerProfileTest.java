package com.ivanocj.cameras.availability.application.model;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerProfileTest {
    @Test
    void retainsImmutableNormalizedPrefixMetadata() {
        var prefixes = new ArrayList<>(List.of(" ffff ", "HHHH", "FFFF"));
        var servers = new ArrayList<>(List.of(InetSocketAddress.createUnresolved("127.0.0.1", 32100)));
        var profile = new ServerProfile("Example", servers, TransportProtocol.XQ, prefixes);
        prefixes.clear();
        servers.clear();
        assertEquals(List.of("FFFF", "HHHH"), profile.prefixes());
        assertEquals(1, profile.servers().size());
        assertThrows(UnsupportedOperationException.class, () -> profile.prefixes().add("GGGG"));
    }
}
