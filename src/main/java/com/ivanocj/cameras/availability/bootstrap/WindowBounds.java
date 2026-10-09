package com.ivanocj.cameras.availability.bootstrap;

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;

/** Pure logical-pixel sizing, including monitors with negative origins or less than the usual minimum. */
final class WindowBounds {
    private WindowBounds() {
    }

    static Rectangle usable(Rectangle screen, Insets insets) {
        return new Rectangle(screen.x + insets.left, screen.y + insets.top,
                Math.max(1, screen.width - insets.left - insets.right),
                Math.max(1, screen.height - insets.top - insets.bottom));
    }

    static Dimension minimum(Dimension preferredMinimum, Rectangle available) {
        return new Dimension(Math.min(preferredMinimum.width, available.width), Math.min(preferredMinimum.height, available.height));
    }

    static Rectangle fit(Dimension packed, Dimension preferredMinimum, Rectangle available) {
        var minimum = minimum(preferredMinimum, available);
        int width = Math.min(available.width, Math.max(minimum.width, packed.width));
        int height = Math.min(available.height, Math.max(minimum.height, packed.height));
        return new Rectangle(available.x + (available.width - width) / 2,
                available.y + (available.height - height) / 2, width, height);
    }
}
