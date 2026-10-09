package com.ivanocj.cameras.availability.infrastructure.configuration;

import com.ivanocj.cameras.availability.testsupport.DeviceFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class XqConfigurationTest {
    static final String FFFF = DeviceFixtures.XQ_FFFF_CONFIGURATION;

    @Test
    void decodesTheHip2pFfffConfiguration() {
        assertEquals(List.of(InetSocketAddress.createUnresolved("52.221.17.159", 32100),
                        InetSocketAddress.createUnresolved("123.56.143.156", 32100),
                        InetSocketAddress.createUnresolved("52.8.0.180", 32100)),
                XqConfiguration.parse(FFFF).servers());
    }

    @Test
    void rejectsAnUnsupportedPrefixConstraintSuffix() {
        assertThrows(IllegalArgumentException.class,
                () -> XqConfiguration.parse(FFFF.replace("-$$", "-AB")));
    }

    @Test
    void rejectsAFalseServerCount() {
        assertThrows(IllegalArgumentException.class, () -> XqConfiguration.parse("LO" + FFFF.substring(2)));
    }
}
