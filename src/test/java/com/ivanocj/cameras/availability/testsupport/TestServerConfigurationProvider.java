package com.ivanocj.cameras.availability.testsupport;

import com.ivanocj.cameras.availability.application.model.ServerConfiguration;
import com.ivanocj.cameras.availability.application.model.ServerProfile;
import com.ivanocj.cameras.availability.application.port.ServerConfigurationProvider;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TestServerConfigurationProvider implements ServerConfigurationProvider {
    /** Stands in for an SY-encoded configuration, whose encoding identifies the PPCS transport. */
    public static final String SY_ENCODED = "SYENCODEDREFERENCE";

    private static final List<ServerProfile> PROFILES = List.of(
            profile("Bell reference", TransportProtocol.PPCS,
                    List.of(), "one.object-camera.com", "174.129.34.188", "four.object-camera.com"),
            profile("HIP2P · SY WWWW/TTTT/UUUU", TransportProtocol.PPCS,
                    List.of("WWWW", "TTTT", "UUUU"), "47.91.222.210", "47.93.34.139", "47.254.79.165"),
            profile("HIP2P · XQ FFFF/HHHH/GGGG", TransportProtocol.XQ,
                    List.of("FFFF", "HHHH", "GGGG"), "52.221.17.159", "123.56.143.156", "52.8.0.180"));

    private final List<ServerProfile> profiles;

    public TestServerConfigurationProvider() {
        this(PROFILES);
    }

    public TestServerConfigurationProvider(List<ServerProfile> profiles) {
        this.profiles = profiles;
    }

    @Override
    public List<ServerProfile> profiles() {
        return profiles;
    }

    @Override
    public ServerConfiguration parse(String input) {
        if (input.strip().equals(DeviceFixtures.XQ_FFFF_CONFIGURATION)) {
            return new ServerConfiguration(profileNamed("HIP2P · XQ FFFF/HHHH/GGGG").servers())
                    .withProtocol(TransportProtocol.XQ);
        }
        if (input.strip().equals(SY_ENCODED)) {
            return new ServerConfiguration(profileNamed("HIP2P · SY WWWW/TTTT/UUUU").servers())
                    .withProtocol(TransportProtocol.PPCS);
        }
        var servers = new ArrayList<InetSocketAddress>();
        for (String entry : input.strip().split(",")) {
            String[] parts = entry.strip().split(":");
            if (parts[0].isBlank()) {
                throw new IllegalArgumentException("Enter a server host.");
            }
            servers.add(InetSocketAddress.createUnresolved(parts[0], parts.length == 2 ? Integer.parseInt(parts[1]) : 32100));
        }
        return new ServerConfiguration(servers);
    }

    public static ServerProfile profileNamed(String name) {
        return PROFILES.stream().filter(profile -> profile.name().equals(name)).findFirst().orElseThrow();
    }

    private static ServerProfile profile(String name, TransportProtocol protocol, List<String> prefixes,
                                         String... hosts) {
        return new ServerProfile(name, Arrays.stream(hosts)
                .map(host -> InetSocketAddress.createUnresolved(host, 32100)).toList(), protocol, prefixes);
    }
}
