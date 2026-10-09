package com.ivanocj.cameras.availability.application.model;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;

public record ServerProfile(String name, List<InetSocketAddress> servers, TransportProtocol protocol,
                            List<String> prefixes) {
    public ServerProfile {
        servers = List.copyOf(servers);
        prefixes = prefixes.stream().map(prefix -> prefix.strip().toUpperCase(Locale.ROOT)).distinct().toList();
    }

    public ServerProfile(String name, List<InetSocketAddress> servers, TransportProtocol protocol) {
        this(name, servers, protocol, List.of());
    }

    public String serverText() {
        return String.join(",", servers.stream()
                .map(server -> server.getHostString() + ":" + server.getPort()).toList());
    }

    @Override
    public String toString() {
        return name;
    }
}
