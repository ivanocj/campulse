package com.ivanocj.cameras.availability.infrastructure.configuration;

import com.ivanocj.cameras.availability.application.model.ServerConfiguration;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HexFormat;

public final class ServerConfigurationParser {
    // Recovered from PPPP_DecodeString in the supplied Linux sample library.
    private static final byte[] DECODER_KEY = HexFormat.of().parseHex(
            "4959433db5bf6da347534f6165e371e9677f02030badb3892b2f35c16b8b9597"
                    + "11e5a70deff1050783fb9d3bc5c713171d1f2529d3df");

    private ServerConfigurationParser() {
    }

    /**
     * Parses an XQ {@code -$$} configuration, an SY-encoded configuration, or a plain host list. The
     * encoded forms identify their transport; a plain host list does not.
     */
    public static ServerConfiguration parse(String input) {
        input = input.strip();
        if (input.endsWith("-$$")) {
            return XqConfiguration.parse(input);
        }
        if (input.matches("[A-Z]{16,}:.+")) {
            throw new IllegalArgumentException("Keyed/encrypted PPCS configurations are not supported by the legacy UDP profile.");
        }
        if (input.matches("[A-Z]{16,}")) {
            return hosts(decodeSy(input)).withProtocol(TransportProtocol.PPCS);
        }
        return hosts(input);
    }

    static ServerConfiguration hosts(String input) {
        return hosts(input, 32100);
    }

    public static ServerConfiguration parseYoosee(String input) {
        var stripped = input.strip();
        if (stripped.endsWith("-$$") || stripped.matches("[A-Z]{16,}(?::.*)?")) { return parse(stripped); }
        return hosts(stripped, 51701);
    }

    private static ServerConfiguration hosts(String input, int defaultPort) {
        var servers = new ArrayList<InetSocketAddress>();
        for (String entry : input.strip().split(",")) {
            if (!entry.strip().matches("[A-Za-z0-9][A-Za-z0-9.-]*(?::[0-9]{1,5})?")) {
                throw new IllegalArgumentException("Enter IPv4 server hosts, optionally with :port, separated by commas.");
            }
            String[] parts = entry.strip().split(":");
            int port = parts.length == 2 ? Integer.parseInt(parts[1]) : defaultPort;
            if (port < 1 || port > 65535) { throw new IllegalArgumentException("Server ports must be between 1 and 65535."); }
            servers.add(InetSocketAddress.createUnresolved(parts[0], port));
        }
        return new ServerConfiguration(servers);
    }

    private static String decodeSy(String input) {
        var decoded = new StringBuilder();
        int rolling = 0x39;
        for (int i = 0; i < input.length() / 2; i++) {
            int encoded = (input.charAt(2 * i) - 'A') * 16 + input.charAt(2 * i + 1) - 'A';
            int plain = (encoded ^ rolling ^ DECODER_KEY[i % DECODER_KEY.length]) & 0xff;
            decoded.append((char) plain);
            rolling ^= plain;
        }
        return decoded.toString();
    }
}
