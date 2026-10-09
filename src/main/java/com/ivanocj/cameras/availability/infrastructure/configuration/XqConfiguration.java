package com.ivanocj.cameras.availability.infrastructure.configuration;

import com.ivanocj.cameras.availability.application.model.ServerConfiguration;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import java.util.Arrays;

public final class XqConfiguration {
    private XqConfiguration() {
    }

    public static ServerConfiguration parse(String input) {
        input = input.strip();
        if (!input.matches("(?:[A-Z]{2})+-\\$\\$")) {
            throw new IllegalArgumentException("Use an even uppercase XQ configuration ending in -$$; prefix constraints are not supported.");
        }
        String encoded = input.substring(0, input.length() - 3);
        var decoded = new StringBuilder();
        for (int i = 0; i < encoded.length(); i += 2) {
            int index = (encoded.charAt(i) - 'A') * 26 + encoded.charAt(i + 1) - 'A';
            decoded.append((char) (index % 94 + 32));
        }
        String[] parts = decoded.toString().split(";", -1);
        int count = Integer.parseInt(parts[0]);
        if (count < 1 || count > 6 || parts.length != count + 1) {
            throw new IllegalArgumentException("XQ configuration must declare one to six matching server entries.");
        }
        return ServerConfigurationParser.hosts(String.join(",", Arrays.copyOfRange(parts, 1, parts.length)))
                .withProtocol(TransportProtocol.XQ);
    }
}
