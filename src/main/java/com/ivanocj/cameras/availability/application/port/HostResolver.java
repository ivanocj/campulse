package com.ivanocj.cameras.availability.application.port;

import java.io.IOException;
import java.net.InetAddress;

@FunctionalInterface
public interface HostResolver {
    InetAddress[] resolve(String host) throws IOException;
}
