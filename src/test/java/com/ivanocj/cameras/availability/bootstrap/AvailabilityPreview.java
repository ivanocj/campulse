package com.ivanocj.cameras.availability.bootstrap;

import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanel;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPreviewFactory;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityTheme;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/** Test-only desktop preview; --snapshot renders offscreen without opening a native window. */
public final class AvailabilityPreview {
    private AvailabilityPreview() {
    }

    public static void main(String[] args) throws Exception {
        boolean snapshot = args.length >= 1 && args[0].equals("--snapshot");
        String mode = args.length == 2 ? args[1] : "default";
        if (args.length > 0 && (!snapshot || args.length > 2 || !List.of("default", "cancel", "small", "wide", "expanded",
                "invalid-identifier", "invalid-servers", "invalid-hichip").contains(mode))) {
            throw new IllegalArgumentException("Use no arguments for the desktop preview, or --snapshot [cancel|small|wide|expanded|invalid-identifier|invalid-servers|invalid-hichip].");
        }
        SwingUtilities.invokeAndWait(() -> {
            AvailabilityTheme.install();
            var panel = AvailabilityPreviewFactory.panel();
            if (snapshot) {
                try {
                    if (mode.equals("cancel")) { AvailabilityPreviewFactory.requestCancelledCheck(panel); }
                    if (mode.equals("expanded")) { AvailabilityPreviewFactory.expandSections(panel); }
                    if (mode.startsWith("invalid-")) { AvailabilityPreviewFactory.requestInvalidInput(panel, mode.substring(8)); }
                    var size = switch (mode) {
                        case "small" -> new Dimension(720, 560);
                        case "wide" -> new Dimension(1100, 700);
                         case "expanded" -> new Dimension(960, 900);
                         case "invalid-identifier", "invalid-servers" -> new Dimension(1100, 820);
                         case "invalid-hichip" -> new Dimension(1100, 900);
                        default -> null;
                    };
                    String fileName = mode.equals("default") ? "availability.png"
                            : mode.equals("cancel") ? "availability-cancelling.png" : "availability-" + mode + ".png";
                    snapshot(panel, fileName, size);
                } catch (IOException failed) {
                    throw new UncheckedIOException(failed);
                } finally {
                    panel.close();
                }
            } else {
                var window = new JFrame();
                AvailabilityApplication.showWindow(window, panel);
                window.setTitle("PREVIEW — no network or private credentials — Device availability");
            }
        });
    }

    static void snapshot(AvailabilityPanel panel, String fileName, Dimension size) throws IOException {
        var root = new JRootPane();
        root.setJMenuBar(panel.menuBar());
        root.setContentPane(new JScrollPane(panel));
        root.setSize(size != null ? size : root.getPreferredSize());
        // Assign widths, then settle nested wrapping/BoxLayout preferred heights before painting.
        for (int pass = 0; pass < 4; pass++) {
            invalidate(root);
            layout(root);
        }
        var image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            root.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        var directory = Path.of("target/ux-rescue-build/preview");
        Files.createDirectories(directory);
        ImageIO.setUseCache(false);
        ImageIO.write(image, "png", directory.resolve(fileName).toFile());
    }

    private static void invalidate(Container container) {
        container.invalidate();
        for (var child : container.getComponents()) {
            if (child instanceof Container nested) { invalidate(nested); }
        }
    }

    private static void layout(Container container) {
        container.doLayout();
        for (var child : container.getComponents()) {
            if (child instanceof Container nested) {
                layout(nested);
            }
        }
    }
}
