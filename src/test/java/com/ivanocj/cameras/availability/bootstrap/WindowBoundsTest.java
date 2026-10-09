package com.ivanocj.cameras.availability.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import org.junit.jupiter.api.Test;

class WindowBoundsTest {
    @Test
    void subtractsTaskbarsAndFitsThePackedWindowInsideTheUsableArea() {
        var available = WindowBounds.usable(new Rectangle(0, 0, 1280, 800), new Insets(24, 0, 48, 0));
        assertEquals(new Rectangle(0, 24, 1280, 728), available);
        var bounds = WindowBounds.fit(new Dimension(1500, 1200), new Dimension(720, 560), available);
        assertEquals(available, bounds);
    }

    @Test
    void respectsNegativeMonitorOriginsAndCentersANaturalWindow() {
        var available = WindowBounds.usable(new Rectangle(-1920, 0, 1920, 1080), new Insets(30, 0, 0, 0));
        var bounds = WindowBounds.fit(new Dimension(960, 600), new Dimension(720, 560), available);
        assertEquals(new Rectangle(-1440, 255, 960, 600), bounds);
        assertTrue(available.contains(bounds));
    }

    @Test
    void aDisplaySmallerThanTheUsualMinimumStillContainsTheWholeWindow() {
        var available = new Rectangle(10, 20, 640, 480);
        assertEquals(new Dimension(640, 480), WindowBounds.minimum(new Dimension(720, 560), available));
        assertEquals(available, WindowBounds.fit(new Dimension(300, 200), new Dimension(720, 560), available));
    }

    @Test
    void aSmallPackedWindowUsesTheNormalMinimumWhenTheScreenAllowsIt() {
        var available = new Rectangle(0, 0, 1280, 800);
        assertEquals(new Rectangle(280, 120, 720, 560),
                WindowBounds.fit(new Dimension(300, 200), new Dimension(720, 560), available));
    }
}
