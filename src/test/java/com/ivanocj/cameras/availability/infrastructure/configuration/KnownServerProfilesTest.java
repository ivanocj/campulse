package com.ivanocj.cameras.availability.infrastructure.configuration;

import com.ivanocj.cameras.availability.application.model.ServerProfile;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnownServerProfilesTest {
    static final String SY_WWWW_CONFIGURATION =
            "EEGDFHBAKJINGNJBEHHNFDEMHINCHENLHLFKBEDLAAJCKCKJDIAFDDPDGOKAIMLLAJNDKGDFPJNGBECDIL";

    @Test
    void recoversTheCurrentHip2pSyPrefixGroup() {
        assertEquals(List.of(InetSocketAddress.createUnresolved("47.91.222.210", 32100),
                        InetSocketAddress.createUnresolved("47.93.34.139", 32100),
                        InetSocketAddress.createUnresolved("47.254.79.165", 32100)),
                profile("HIP2P · SY WWWW/TTTT/UUUU").servers());
    }

    @Test
    void recoversTheOlderHip2pSyDefaults() {
        assertEquals(List.of(InetSocketAddress.createUnresolved("58.96.170.32", 32100),
                        InetSocketAddress.createUnresolved("123.57.136.155", 32100),
                        InetSocketAddress.createUnresolved("54.183.36.158", 32100)),
                profile("HIP2P · SY AAAA–EEEE").servers());
    }

    @Test
    void recoversTheAaesDeployment() {
        assertEquals(List.of(InetSocketAddress.createUnresolved("190.210.197.185", 32100),
                        InetSocketAddress.createUnresolved("190.210.197.181", 32100),
                        InetSocketAddress.createUnresolved("190.210.197.184", 32100)),
                profile("HIP2P · AAES").servers());
    }

    @Test
    void preservesDericamDnsNamesFromItsDeaaConfiguration() {
        assertEquals(List.of(InetSocketAddress.createUnresolved("52.8.254.146", 32100),
                        InetSocketAddress.createUnresolved("p2p3.dericam.com", 32100),
                        InetSocketAddress.createUnresolved("p2p2.dericam.com", 32100)),
                profile("HIP2P · Dericam DEAA").servers());
    }

    @Test
    void recoversTheFdtaaDeployment() {
        assertEquals(List.of(InetSocketAddress.createUnresolved("54.67.46.219", 32100),
                        InetSocketAddress.createUnresolved("52.57.62.100", 32100),
                        InetSocketAddress.createUnresolved("54.165.34.140", 32100)),
                profile("HIP2P · FDTAA").servers());
    }

    @Test
    void recoversTheSecruiDeployment() {
        assertEquals(List.of(InetSocketAddress.createUnresolved("47.91.94.133", 32100),
                        InetSocketAddress.createUnresolved("47.89.252.25", 32100),
                        InetSocketAddress.createUnresolved("101.37.148.156", 32100)),
                profile("HIP2P · SECRUI").servers());
    }

    @Test
    void keepsTheOriginalBellReferenceAsTheInitialProfile() {
        assertEquals(new ServerProfile("Bell reference",
                        List.of(InetSocketAddress.createUnresolved("one.object-camera.com", 32100),
                                InetSocketAddress.createUnresolved("174.129.34.188", 32100),
                                InetSocketAddress.createUnresolved("four.object-camera.com", 32100)),
                        TransportProtocol.PPCS),
                KnownServerProfiles.all().getFirst());
    }

    @Test
    void presentsAProfileNameInsteadOfAnOpaqueConfigurationInTheSelector() {
        assertEquals("HIP2P · SY WWWW/TTTT/UUUU", profile("HIP2P · SY WWWW/TTTT/UUUU").toString());
    }

    @Test
    void exposesEditableDecodedEndpointsWithTheirPorts() {
        assertEquals("47.91.222.210:32100,47.93.34.139:32100,47.254.79.165:32100",
                profile("HIP2P · SY WWWW/TTTT/UUUU").serverText());
    }

    @Test
    void addsTheNativeXqFfffHhhhGgggServerProfile() {
        assertEquals(XqConfiguration.parse(XqConfigurationTest.FFFF).servers(),
                profile("HIP2P · XQ FFFF/HHHH/GGGG").servers());
    }

    @Test
    void aProfileCarriesItsTransportFamilyAfterItsEndpointsAreDecoded() {
        assertEquals(TransportProtocol.XQ, profile("HIP2P · XQ FFFF/HHHH/GGGG").protocol());
    }

    @Test
    void addsTheNativeXqIiiiJjjjKkkkDeployment() {
        assertEquals(List.of(InetSocketAddress.createUnresolved("47.94.37.184", 32100),
                        InetSocketAddress.createUnresolved("120.76.28.153", 32100),
                        InetSocketAddress.createUnresolved("47.89.48.227", 32100),
                        InetSocketAddress.createUnresolved("47.88.23.67", 32100)),
                profile("HIP2P · XQ IIII/JJJJ/KKKK").servers());
    }

    @Test
    void addsTheNativeXqXxxxYyyyZzzzDeployment() {
        assertEquals(List.of(InetSocketAddress.createUnresolved("47.94.56.41", 32100),
                        InetSocketAddress.createUnresolved("47.91.165.44", 32100),
                        InetSocketAddress.createUnresolved("47.88.0.50", 32100)),
                profile("HIP2P · XQ XXXX/YYYY/ZZZZ").servers());
    }

    @Test
    void explicitlyAssociatesEveryRecoveredPrefixWithItsDeployment() {
        var expected = Map.of(
                "HIP2P · SY WWWW/TTTT/UUUU", List.of("WWWW", "TTTT", "UUUU"),
                "HIP2P · SY AAAA–EEEE", List.of("AAAA", "BBBB", "CCCC", "DDDD", "EEEE"),
                "HIP2P · AAES", List.of("AAES"),
                "HIP2P · Dericam DEAA", List.of("DEAA"),
                "HIP2P · FDTAA", List.of("FDTAA"),
                "HIP2P · SECRUI", List.of("SECRUI"),
                "HIP2P · XQ FFFF/HHHH/GGGG", List.of("FFFF", "HHHH", "GGGG"),
                "HIP2P · XQ IIII/JJJJ/KKKK", List.of("IIII", "JJJJ", "KKKK"),
                "HIP2P · XQ XXXX/YYYY/ZZZZ", List.of("XXXX", "YYYY", "ZZZZ"));
        expected.forEach((name, prefixes) -> assertEquals(prefixes, profile(name).prefixes()));
        assertEquals(List.of(), profile("Bell reference").prefixes(),
                "The reference configuration must not become a fallback for unknown prefixes");
    }

    static ServerProfile profile(String name) {
        return KnownServerProfiles.all().stream().filter(profile -> profile.name().equals(name))
                .findFirst().orElseThrow();
    }
}
