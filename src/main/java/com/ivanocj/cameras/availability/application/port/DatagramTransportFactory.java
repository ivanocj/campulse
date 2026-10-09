package com.ivanocj.cameras.availability.application.port;

import java.io.IOException;
import java.net.InetSocketAddress;

@FunctionalInterface
public interface DatagramTransportFactory {
    DatagramTransport open(InetSocketAddress routeTo) throws IOException;
}
