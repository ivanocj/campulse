package com.ivanocj.cameras.availability.application.service;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import com.ivanocj.cameras.availability.domain.model.IotVideoDeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.configuration.DefaultServerConfigurationProvider;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YooseeBatchRoutingTest {
    @Test
    void taggedFamiliesRouteToDedicatedBackendsAndNeverToPpcsPrefixDefaults() throws Exception {
        var requests = new ArrayList<ProbeRequest>();
        var service = new BatchAvailabilityService((request, cancel, progress) -> {
            requests.add(request);
            return new ProbeResult(ProbeResult.Status.UNSUPPORTED);
        }, new DefaultServerConfigurationProvider());
        var entries = service.read(new StringReader("GWELL:001234\nIOTVIDEO:Ab_01234\n01234\n"));
        assertTrue(entries.get(0).canCheck());
        assertTrue(entries.get(1).canCheck());
        assertFalse(entries.get(2).canCheck(), "Untagged numeric IDs cannot safely identify a backend");
        service.check(entries, Duration.ofSeconds(1), false, () -> false, result -> {}, message -> {});
        assertEquals(List.of(TransportProtocol.GWELL, TransportProtocol.IOTVIDEO),
                requests.stream().map(ProbeRequest::protocol).toList());
        assertEquals(new GwellDeviceId("001234"), requests.getFirst().identifier());
        assertEquals(new IotVideoDeviceId("Ab_01234"), requests.getLast().identifier());
        assertTrue(requests.stream().allMatch(request -> !request.servers().isEmpty()
                && request.servers().stream().allMatch(server -> server.getPort() == 51701)));
    }
}
