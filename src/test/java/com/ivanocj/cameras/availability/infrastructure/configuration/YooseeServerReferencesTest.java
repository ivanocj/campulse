package com.ivanocj.cameras.availability.infrastructure.configuration;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YooseeServerReferencesTest {
    @Test
    void preservesProductionNamesAndNativeFallbackOrderWithoutTurningThemIntoLegacyUdpPresets() {
        var references = YooseeServerReferences.production();
        assertEquals(TransportProtocol.GWELL, references.getFirst().protocol());
        assertEquals(List.of("p2p1.cloudlinks.cn", "p2p3.cloud-links.net", "p2p2.cloudlinks.cn", "p2p4.cloud-links.net",
                "p2p5.cloudlinks.cn", "p2p6.cloudlinks.cn", "p2p7.cloudlinks.cn", "p2p8.cloudlinks.cn",
                "p2p9.cloudlinks.cn", "p2p10.cloudlinks.cn"), references.getFirst().hosts());
        assertEquals(List.of("list.iotvideo.cloudlinks.cn"), references.getLast().hosts());
        assertEquals(TransportProtocol.IOTVIDEO, references.getLast().protocol());
        assertThrows(UnsupportedOperationException.class, () -> references.getFirst().hosts().add("test.example"));
        for (var profile : KnownServerProfiles.all()) {
            if (!profile.protocol().legacyUdp()) {
                assertTrue(profile.servers().stream().allMatch(server -> server.getPort() == 51701));
                assertTrue(profile.prefixes().isEmpty());
            }
        }
        assertEquals(2, KnownServerProfiles.all().stream().filter(profile -> profile.name().endsWith("LAN only")).count());
        assertTrue(KnownServerProfiles.all().stream().anyMatch(profile -> profile.protocol() == TransportProtocol.GWELL && profile.servers().isEmpty()));
    }
}
