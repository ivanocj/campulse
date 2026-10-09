package com.ivanocj.cameras.availability.integration;

import com.ivanocj.cameras.availability.application.model.BatchEntry;
import com.ivanocj.cameras.availability.application.service.BatchAvailabilityService;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.configuration.DefaultServerConfigurationProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyntheticBatchImportTest {
    @Test
    void theSyntheticFixtureImportsAllDescriptionsAndRoutesOnlyKnownPrefixesWithoutNetworkWork() throws Exception {
        var service = new BatchAvailabilityService((request, cancelled, progress) -> {
            throw new AssertionError("The synthetic fixture must not contact any devices during tests");
        }, new DefaultServerConfigurationProvider());
        var sample = getClass().getResourceAsStream("/batch-synthetic.csv");
        assertNotNull(sample, "The fabricated CSV fixture must be available as a test resource");
        try (var reader = new InputStreamReader(sample, StandardCharsets.UTF_8)) {
            var entries = service.readCsv(reader);
            assertEquals(17, entries.size());
            assertEquals("AAAA-000001-DEMO", entries.getFirst().code());
            assertEquals("Synthetic camera 01", entries.getFirst().description());
            assertEquals("IOTVIDEO:demo-device-key", entries.getLast().code());
            assertEquals("Synthetic IoTVideo key", entries.getLast().description());
            assertTrue(entries.stream().allMatch(entry -> !entry.description().isEmpty()));
            assertEquals(8, entries.stream().filter(entry -> entry.canCheck()
                    && entry.profile().orElseThrow().protocol() == TransportProtocol.PPCS).count());
            assertEquals(5, entries.stream().filter(entry -> entry.canCheck()
                    && entry.profile().orElseThrow().protocol() == TransportProtocol.XQ).count());
            assertEquals(1, entries.stream().filter(entry -> entry.canCheck()
                    && entry.profile().orElseThrow().protocol() == TransportProtocol.GWELL).count());
            assertEquals(1, entries.stream().filter(entry -> entry.canCheck()
                    && entry.profile().orElseThrow().protocol() == TransportProtocol.IOTVIDEO).count());
            assertEquals(List.of("NNNN-000014-DEMO", "WXH-000015-DEMO"),
                    entries.stream().filter(entry -> !entry.canCheck()).map(BatchEntry::code).toList());
        }
    }
}
