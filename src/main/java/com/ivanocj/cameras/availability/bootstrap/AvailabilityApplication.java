package com.ivanocj.cameras.availability.bootstrap;

import com.ivanocj.cameras.availability.application.service.AvailabilityCheckService;
import com.ivanocj.cameras.availability.application.service.GwellAvailabilityService;
import com.ivanocj.cameras.availability.application.service.ProtocolProbeRouter;
import com.ivanocj.cameras.availability.infrastructure.configuration.DefaultServerConfigurationProvider;
import com.ivanocj.cameras.availability.infrastructure.configuration.FileYooseeCredentials;
import com.ivanocj.cameras.availability.infrastructure.logging.ApplicationLogging;
import com.ivanocj.cameras.availability.infrastructure.probe.UdpSessionProbe;
import com.ivanocj.cameras.availability.infrastructure.probe.GwellLanDiscovery;
import com.ivanocj.cameras.availability.infrastructure.probe.GwellRemoteProbe;
import com.ivanocj.cameras.availability.infrastructure.probe.IotVideoRemoteProbe;
import com.ivanocj.cameras.availability.infrastructure.protocol.GwellDiscoveryMessages;
import com.ivanocj.cameras.availability.infrastructure.transport.Ipv4BroadcastTargets;
import com.ivanocj.cameras.availability.infrastructure.transport.UdpTransport;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanel;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityTheme;
import com.ivanocj.cameras.availability.presentation.swing.PreferencesFormMemory;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AvailabilityApplication {
    private static final Logger LOG = LoggerFactory.getLogger(AvailabilityApplication.class);
    static final Dimension MINIMUM_WINDOW = new Dimension(720, 560);

    private AvailabilityApplication() {
    }

    public static void main(String[] args) {
        ApplicationLogging.initialize();
        LOG.info("event=application_started java_feature={} pid={}", Runtime.version().feature(), ProcessHandle.current().pid());
        start(panel -> showWindow(new JFrame(), panel));
    }

    static void start(Consumer<AvailabilityPanel> showView) {
        SwingUtilities.invokeLater(() -> {
            AvailabilityTheme.install();
            var legacy = new AvailabilityCheckService(UdpTransport::new, InetAddress::getAllByName, System::nanoTime,
                    UdpSessionProbe::new);
            var discovery = new GwellLanDiscovery(route -> new UdpTransport(route, GwellDiscoveryMessages.PORT),
                    () -> Ipv4BroadcastTargets.forPort(GwellDiscoveryMessages.PORT), System::nanoTime);
            var credentials = new FileYooseeCredentials(Optional.ofNullable(System.getenv("YOOSEE_CREDENTIALS_FILE"))
                    .filter(value -> !value.isBlank()).map(Path::of));
            var gwell = new GwellRemoteProbe(credentials, InetAddress::getAllByName, Clock.systemUTC());
            var iotvideo = new IotVideoRemoteProbe(credentials, InetAddress::getAllByName, Clock.systemUTC());
            var runner = new ProtocolProbeRouter(legacy, new GwellAvailabilityService(discovery, gwell), iotvideo);
            var memory = new PreferencesFormMemory(Preferences.userNodeForPackage(AvailabilityApplication.class));
            showView.accept(new AvailabilityPanel(runner, new DefaultServerConfigurationProvider(), memory,
                    selected -> selected.ifPresentOrElse(credentials::select, credentials::clear)));
            LOG.info("event=desktop_started");
        });
    }

    static void showWindow(JFrame window, AvailabilityPanel panel) {
        window.setTitle("CamPulse · Camera connectivity");
        window.setJMenuBar(panel.menuBar());
        // Scrolls rather than clipping when the screen is smaller than the form's natural size.
        var content = new JScrollPane(panel);
        content.setBorder(BorderFactory.createEmptyBorder());
        window.setContentPane(content);
        window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                panel.focusIdentifier();
            }

            @Override
            public void windowClosing(WindowEvent event) {
                panel.close();
            }
        });
        window.pack();
        var graphics = window.getGraphicsConfiguration();
        if (graphics == null) {
            window.setMinimumSize(MINIMUM_WINDOW);
            window.setLocationRelativeTo(null);
        } else {
            var available = WindowBounds.usable(graphics.getBounds(), Toolkit.getDefaultToolkit().getScreenInsets(graphics));
            window.setMinimumSize(WindowBounds.minimum(MINIMUM_WINDOW, available));
            window.setBounds(WindowBounds.fit(window.getSize(), MINIMUM_WINDOW, available));
        }
        window.setVisible(true);
    }
}
