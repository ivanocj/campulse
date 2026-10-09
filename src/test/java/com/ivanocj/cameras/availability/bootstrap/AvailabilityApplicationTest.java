package com.ivanocj.cameras.availability.bootstrap;

import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanel;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.awt.event.WindowListener;
import java.awt.Container;
import java.awt.Dimension;
import java.lang.reflect.Modifier;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AvailabilityApplicationTest {
    @Test
    void bootsTheDesktopViewOnTheEventDispatchThread() throws Exception {
        var started = new CountDownLatch(1);
        var onEdt = new AtomicBoolean();
        AvailabilityApplication.start(panel -> {
            onEdt.set(SwingUtilities.isEventDispatchThread());
            started.countDown();
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(onEdt.get());
    }

    @Test
    void showsTheDesktopWindowAndConnectsItsCloseEventToCleanup() throws Exception {
        var window = mock(JFrame.class);
        var panel = spy(newPanel());
        SwingUtilities.invokeAndWait(() -> AvailabilityApplication.showWindow(window, panel));
        var content = ArgumentCaptor.forClass(Container.class);
        verify(window).setContentPane(content.capture());
        assertSame(panel, ((JScrollPane) content.getValue()).getViewport().getView());
        verify(window).setJMenuBar(panel.menuBar());
        verify(window).setVisible(true);
        verify(window).setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        var listener = ArgumentCaptor.forClass(WindowListener.class);
        verify(window).addWindowListener(listener.capture());
        listener.getValue().windowClosing(null);
        verify(panel).close();
    }

    @Test
    void sizesTheWindowFromItsContentWithALaptopFriendlyMinimum() throws Exception {
        var window = mock(JFrame.class);
        SwingUtilities.invokeAndWait(() -> AvailabilityApplication.showWindow(window, newPanel()));
        verify(window).setTitle("CamPulse · Camera connectivity");
        verify(window).pack();
        verify(window).setMinimumSize(new Dimension(720, 560));
        verify(window).setLocationRelativeTo(null);
    }

    @Test
    void requestsIdentifierFocusOnlyWhenTheWindowFirstOpens() throws Exception {
        var window = mock(JFrame.class);
        var panel = spy(newPanel());
        SwingUtilities.invokeAndWait(() -> AvailabilityApplication.showWindow(window, panel));
        var listener = ArgumentCaptor.forClass(WindowListener.class);
        verify(window).addWindowListener(listener.capture());
        verify(panel, never()).focusIdentifier();
        SwingUtilities.invokeAndWait(() -> {
            listener.getValue().windowActivated(null);
            listener.getValue().windowDeactivated(null);
        });
        verify(panel, never()).focusIdentifier();
        SwingUtilities.invokeAndWait(() -> listener.getValue().windowOpened(null));
        verify(panel).focusIdentifier();
    }

    @Test
    void exposesTheExecutableJarEntryPoint() throws Exception {
        var entry = AvailabilityApplication.class.getMethod("main", String[].class);
        assertTrue(Modifier.isPublic(entry.getModifiers()) && Modifier.isStatic(entry.getModifiers())
                && entry.getReturnType() == void.class);
    }

    private static AvailabilityPanel newPanel() {
        return new AvailabilityPanel((request, cancelled, progress) -> new ProbeResult(ProbeResult.Status.INCONCLUSIVE),
                new TestServerConfigurationProvider());
    }
}
