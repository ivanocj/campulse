package com.ivanocj.cameras.availability.integration.logging;

import com.ivanocj.cameras.availability.application.port.DatagramTransport;
import com.ivanocj.cameras.availability.application.service.AvailabilityCheckService;
import com.ivanocj.cameras.availability.application.service.BatchAvailabilityService;
import com.ivanocj.cameras.availability.infrastructure.logging.ApplicationLogging;
import com.ivanocj.cameras.availability.infrastructure.probe.UdpSessionProbe;
import com.ivanocj.cameras.availability.infrastructure.protocol.MessageType;
import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsMessages;
import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsPacket;
import com.ivanocj.cameras.availability.testsupport.FakeDatagramTransport;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;

/** Child-JVM fixture: fake DNS/datagrams only; exercises the real facade, backend, hooks, batch and session stack. */
public final class LoggingProcessFixture {
    private LoggingProcessFixture() {}

    public static void main(String[] args) throws Exception {
        ApplicationLogging.initialize();
        var logger = LoggerFactory.getLogger(LoggingProcessFixture.class);
        logger.info("event=fixture_started");
        if (args.length > 0 && args[0].equals("minimal")) { return; }
        var clocks = ThreadLocal.withInitial(AtomicLong::new);
        var runner = new AvailabilityCheckService(route -> {
            var transport = new FakeDatagramTransport() {
                @Override
                public Optional<DatagramTransport.Received> receive(Duration wait) {
                    clocks.get().addAndGet(wait.toNanos());
                    return Optional.ofNullable(incoming.poll());
                }
            };
            transport.incoming.add(new DatagramTransport.Received(
                    new PpcsPacket(MessageType.HELLO_ACK, PpcsMessages.address(new InetSocketAddress("198.51.100.99", 45001))).encode(), route));
            transport.incoming.add(new DatagramTransport.Received(
                    new PpcsPacket(MessageType.PUNCH_TO, PpcsMessages.address(new InetSocketAddress("198.51.100.7", 32108))).encode(), route));
            transport.incoming.add(new DatagramTransport.Received(new byte[] {1, 2, 3}, route));
            return transport;
        }, host -> new InetAddress[] {InetAddress.getLoopbackAddress()}, () -> clocks.get().get(), UdpSessionProbe::new);
        var batch = new BatchAvailabilityService(runner, new TestServerConfigurationProvider());
        var csv = new StringBuilder("Serial,Description\n");
        for (int serial = 1; serial <= 100; serial++) {
            csv.append(serial % 2 == 0 ? "FFFF-" : "WWWW-").append(serial).append("-A,private-description-secret\n");
        }
        var results = batch.check(batch.readCsv(new StringReader(csv.toString())), Duration.ofSeconds(2), false, 8,
                () -> false, row -> {}, message -> {});
        if (results.size() != 100) { throw new AssertionError("Missing probe results"); }
        logger.info("event=fixture_completed rows={}", results.size());
        // Do not close explicitly: the production shutdown hook must flush every queued event.
    }
}
