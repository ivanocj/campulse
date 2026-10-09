package com.ivanocj.cameras.availability.domain.model;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.infrastructure.protocol.ProtocolMessages;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YooseeIdentifierTest {
    @Test
    void leadingZeroSelectsTheGwellIdentityNamespaceRatherThanBeingDiscarded() {
        assertEquals(1234, new GwellDeviceId("1234").wireValue());
        assertEquals(0x800004d2, new GwellDeviceId(" 01234 ").wireValue());
        assertEquals("01234", new GwellDeviceId(" 01234 ").value());
        assertNotEquals(TransportProtocol.GWELL.sessionKey(new GwellDeviceId("01234")),
                TransportProtocol.GWELL.sessionKey(new GwellDeviceId("1234")));
        assertEquals(TransportProtocol.GWELL.sessionKey(new GwellDeviceId("001234")),
                TransportProtocol.GWELL.sessionKey(new GwellDeviceId("01234")));
        assertEquals("01234", GwellDeviceId.fromWire(0x800004d2).value());
    }

    @Test
    void gwellIdsUsePositiveSigned31BitSerialsBeforeApplyingTheNamespaceBit() {
        assertEquals(0x7fffffff, new GwellDeviceId("2147483647").wireValue());
        assertEquals(0xffffffff, new GwellDeviceId("02147483647").wireValue());
        for (var invalid : List.of("", "0", "00", "-1", "+1", "123A", "2147483648", "4294967295")) {
            assertThrows(IllegalArgumentException.class, () -> new GwellDeviceId(invalid), invalid);
        }
    }

    @Test
    void iotvideoIdentifiersRemainOpaqueAndCaseSensitive() {
        assertEquals("Ab_01234", new IotVideoDeviceId(" Ab_01234 ").value());
        assertNotEquals(new IotVideoDeviceId("Ab_01234"), new IotVideoDeviceId("AB_01234"));
        assertThrows(IllegalArgumentException.class, () -> new IotVideoDeviceId(""));
        assertThrows(IllegalArgumentException.class, () -> new IotVideoDeviceId("a\nb"));
    }

    @Test
    void eachBackendRequiresItsOwnIdentifierAndTheLegacyCodecRejectsYoosee() {
        assertInstanceOf(GwellDeviceId.class, TransportProtocol.GWELL.parseIdentifier("01234"));
        assertInstanceOf(IotVideoDeviceId.class, TransportProtocol.IOTVIDEO.parseIdentifier("Ab_01234"));
        assertThrows(IllegalArgumentException.class, () -> TransportProtocol.PPCS.validate(new GwellDeviceId("1234")));
        assertThrows(IllegalArgumentException.class, () -> TransportProtocol.GWELL.validate(new DeviceId("FFFF-1-A")));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolMessages(TransportProtocol.GWELL));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolMessages(TransportProtocol.IOTVIDEO));
        var request = new ProbeRequest(new GwellDeviceId("01234"), List.of(), Duration.ofSeconds(1), true,
                TransportProtocol.GWELL);
        assertEquals("01234", request.identifier().value());
        assertThrows(IllegalStateException.class, request::did, "A numeric ID must never turn into a composite DID");
    }

    @Test
    void batchFamiliesMustBeExplicitInsteadOfGuessingWhichNumericProtocolWasIntended() {
        assertEquals(new GwellDeviceId("001234"), DeviceIdentifier.parseBatch("gwell:001234"));
        assertEquals(new IotVideoDeviceId("Ab_01234"), DeviceIdentifier.parseBatch("IOTVIDEO:Ab_01234"));
        assertEquals(new DeviceId("FFFF-1-A"), DeviceIdentifier.parseBatch("ffff-1-a"));
        assertThrows(IllegalArgumentException.class, () -> DeviceIdentifier.parseBatch("01234"));
    }
}
