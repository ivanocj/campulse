package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.RemotePresence;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.configuration.DefaultServerConfigurationProvider;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;

/** Manual-preview fixture only: no network, platform Preferences, or private account files. */
public final class AvailabilityPreviewFactory {
    private AvailabilityPreviewFactory() {
    }

    public static AvailabilityPanel panel() {
        var panel = new AvailabilityPanel(AvailabilityPreviewFactory::check,
                new DefaultServerConfigurationProvider(), FormMemory.NONE);
        // Never open a filesystem browser: use fabricated test data or cancel account selection.
        panel.chooseBatchFile = () -> Optional.of(Path.of("src/test/resources/batch-synthetic.csv"));
        panel.chooseCredentialsFile = Optional::empty;
        return panel;
    }

    /** Creates only a repository-local, synthetic input file; call outside the EDT. */
    public static Path batchFixture() throws IOException {
        var root = Path.of("target/ux-rescue-build/preview");
        Files.createDirectories(root);
        var directory = Files.createTempDirectory(root, "batch-input-");
        return Files.writeString(directory.resolve("batch-inspection.csv"), """
                Serial,Description
                FFFF-1-A,"Front door, north"
                FFFF-1-A,Câmera entrada [north]
                WWWW-2-B,Indoor camera
                DEAA-3-C,Garage camera
                GWELL:01234,GWell account presence
                IOTVIDEO:Ab_01234,Case-sensitive key
                MISSING-4-D,Unknown deployment
                """);
    }

    public static AvailabilityPanel batchPanel(Path file) {
        return batchPanel(file, (request, cancelled, progress) -> {
            var status = request.protocol() == TransportProtocol.GWELL ? ProbeResult.Status.AUTH_REQUIRED
                    : request.protocol() == TransportProtocol.IOTVIDEO ? ProbeResult.Status.REPORTED_ONLINE
                    : request.identifier().value().startsWith("FFFF-") ? ProbeResult.Status.REACHABLE_P2P
                    : request.identifier().value().startsWith("WWWW-") ? ProbeResult.Status.REACHABLE_RELAY : ProbeResult.Status.TIMED_OUT;
            long millis = status == ProbeResult.Status.REACHABLE_P2P ? 2 : status == ProbeResult.Status.TIMED_OUT ? 100 : 10;
            var mode = status == ProbeResult.Status.REACHABLE_P2P ? P2pInformation.ConnectionMode.DIRECT_P2P
                    : status == ProbeResult.Status.REACHABLE_RELAY ? P2pInformation.ConnectionMode.UDP_RELAY : P2pInformation.ConnectionMode.NONE;
            Optional<InetSocketAddress> peer = mode == P2pInformation.ConnectionMode.NONE ? Optional.empty()
                    : Optional.of(new InetSocketAddress(mode == P2pInformation.ConnectionMode.UDP_RELAY ? "192.0.2.8" : "198.51.100.8", 32108));
            var information = new P2pInformation(Optional.of(request.protocol()), mode, Optional.empty(), List.of(), List.of(),
                    mode == P2pInformation.ConnectionMode.DIRECT_P2P ? peer.stream().toList() : List.of(),
                    mode == P2pInformation.ConnectionMode.UDP_RELAY ? peer.stream().toList() : List.of(), Map.of());
            var now = Instant.now();
            var presence = status == ProbeResult.Status.REPORTED_ONLINE ? Optional.of(new RemotePresence(RemotePresence.State.ONLINE,
                    TransportProtocol.IOTVIDEO, RemotePresence.Source.IOTVIDEO_MODEL, Optional.of(now), now)) : Optional.<RemotePresence>empty();
            return new ProbeResult(status, "Synthetic preview result — no network or credentials used.",
                    status != ProbeResult.Status.AUTH_REQUIRED, peer, Optional.empty(), Duration.ofMillis(millis), information, List.of(), presence);
        });
    }

    public static AvailabilityPanel batchPanel(Path file, ProbeRunner runner) {
        var panel = new AvailabilityPanel(runner, new DefaultServerConfigurationProvider(), FormMemory.NONE);
        panel.chooseBatchFile = () -> Optional.of(file);
        panel.chooseCredentialsFile = Optional::empty;
        return panel;
    }

    /** Preferences measurement only: neither chooser may browse user files. */
    public static AvailabilityPanel memoryPanel(FormMemory memory, ProbeRunner runner) {
        var panel = new AvailabilityPanel(runner, new DefaultServerConfigurationProvider(), memory);
        panel.chooseBatchFile = Optional::empty;
        panel.chooseCredentialsFile = Optional::empty;
        return panel;
    }

    /** Drives actual form actions so a headless render can inspect the pending-cancellation UI. */
    public static void requestCancelledCheck(AvailabilityPanel panel) {
        component(panel, "did", JTextField.class).setText("FFFF-1-A");
        component(panel, "check", JButton.class).doClick(0);
        component(panel, "cancel", JButton.class).doClick(0);
    }

    public static void expandSections(AvailabilityPanel panel) {
        for (var name : List.of("connectionSettings", "accountSettings", "evidence", "hichipTools", "diagnostics")) {
            var toggle = component(panel, name + "Toggle", JToggleButton.class);
            if (!toggle.isSelected()) { toggle.doClick(0); }
        }
    }

    /** Rejected inputs drive the real form handlers without invoking even the fake runner. */
    public static JComponent requestInvalidInput(AvailabilityPanel panel, String field) {
        switch (field) {
            case "identifier" -> {
                component(panel, "did", JTextField.class).setText("broken");
                component(panel, "check", JButton.class).doClick(0);
                return component(panel, "did", JTextField.class);
            }
            case "servers" -> {
                component(panel, "did", JTextField.class).setText("FFFF-1-A");
                component(panel, "servers", JTextArea.class).setText("127.0.0.1:65536");
                component(panel, "check", JButton.class).doClick(0);
                return component(panel, "servers", JTextArea.class);
            }
            case "hichip" -> {
                component(panel, "uidSerial", JTextField.class).setText("abc");
                component(panel, "uidGenerate", JButton.class).doClick(0);
                return component(panel, "uidSerial", JTextField.class);
            }
            default -> throw new IllegalArgumentException("Unknown validation-preview field: " + field);
        }
    }

    private static ProbeResult check(ProbeRequest request, BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        long started = System.nanoTime();
        boolean wasCancelled;
        try {
            progress.accept("Preview operation — no network traffic.");
            long deadline = started + Duration.ofSeconds(2).toNanos();
            while (!cancelled.getAsBoolean() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            wasCancelled = cancelled.getAsBoolean();
            if (wasCancelled) {
                progress.accept("Preview cleanup delay — inputs remain locked.");
                Thread.sleep(1000);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Preview fixture interrupted.", interrupted);
        }
        return new ProbeResult(wasCancelled ? ProbeResult.Status.CANCELLED : ProbeResult.Status.INCONCLUSIVE,
                "Preview only — no camera or server was contacted.", false, Optional.empty(), Optional.empty(),
                Duration.ofNanos(System.nanoTime() - started),
                new P2pInformation(Optional.of(request.protocol()), P2pInformation.ConnectionMode.NONE,
                        Optional.empty(), List.of(), List.of(), List.of(), List.of(), Map.of()));
    }

    public static <T extends Component> T component(Container root, String name, Class<T> type) {
        return findComponent(root, name, type)
                .orElseThrow(() -> new IllegalArgumentException("Missing preview component: " + name));
    }

    private static <T extends Component> Optional<T> findComponent(Container root, String name, Class<T> type) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) { return Optional.of(type.cast(child)); }
            if (child instanceof Container nested) {
                var found = findComponent(nested, name, type);
                if (found.isPresent()) { return found; }
            }
        }
        return Optional.empty();
    }
}
