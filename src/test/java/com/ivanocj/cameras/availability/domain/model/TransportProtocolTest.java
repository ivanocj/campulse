package com.ivanocj.cameras.availability.domain.model;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TransportProtocolTest {
    @Test
    void xqRejectsIdsThatWouldBeTruncatedOnItsTwentyByteWireField() {
        assertThrows(IllegalArgumentException.class,
                () -> TransportProtocol.XQ.validate(new DeviceId("ABCDEFG-12345678-ABCDEF")));
    }
}
