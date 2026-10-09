package com.ivanocj.cameras.availability.infrastructure.transport;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public final class Ipv4BroadcastTargets {
    private Ipv4BroadcastTargets() {
    }

    /** Limited plus directed broadcasts; no DNS, subnet sweeping, or traffic outside the local interfaces. */
    public static List<InetSocketAddress> forPort(int port) {
        var targets = new LinkedHashSet<InetSocketAddress>();
        targets.add(new InetSocketAddress("255.255.255.255", port));
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (var network : Collections.list(interfaces)) {
                    if (network.isUp() && !network.isLoopback()) {
                        for (var address : network.getInterfaceAddresses()) {
                            if (address.getBroadcast() instanceof Inet4Address broadcast) {
                                targets.add(new InetSocketAddress(broadcast, port));
                            }
                        }
                    }
                }
            }
        } catch (SocketException unavailable) {
            // Limited broadcast remains usable even when interface enumeration is unavailable.
        }
        return List.copyOf(targets);
    }
}
