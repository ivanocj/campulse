package com.ivanocj.cameras.availability.presentation.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.formdev.flatlaf.FlatDarkLaf;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;

class AvailabilityThemeTest {
    @Test
    void installsAModernReadableThemeWithoutLoadingNativeLibraries() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AvailabilityTheme.install();
            assertInstanceOf(FlatDarkLaf.class, UIManager.getLookAndFeel());
            assertEquals("false", System.getProperty("flatlaf.useNativeLibrary"));
            assertEquals(14, UIManager.getFont("defaultFont").getSize());
        });
    }
}
