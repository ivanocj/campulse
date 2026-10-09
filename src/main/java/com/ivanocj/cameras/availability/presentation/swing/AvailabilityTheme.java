package com.ivanocj.cameras.availability.presentation.swing;

import com.formdev.flatlaf.FlatDarkLaf;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter.Tone;
import java.awt.Color;
import java.awt.Font;
import javax.swing.UIManager;

/** The application's single source of colours and fonts. */
public final class AvailabilityTheme {
    public static final Color BACKGROUND = new Color(0x14171f);
    public static final Color CARD = new Color(0x20242e);
    public static final Color CARD_BORDER = new Color(0x343b4b);
    public static final Color ACCENT = new Color(0x9a8af7);
    public static final Color ON_ACCENT = BACKGROUND;
    public static final Color SECTION_TITLE = new Color(0xa89bff);
    public static final Color MUTED = new Color(0xaab2c4);
    public static final Color POSITIVE = new Color(0x5fd38d);
    public static final Color CAUTION = new Color(0xf2c14e);
    public static final Color NEGATIVE = new Color(0xff6b6b);

    private AvailabilityTheme() {
    }

    public static void install() {
        System.setProperty("flatlaf.useNativeLibrary", "false");
        FlatDarkLaf.setup();
        UIManager.put("defaultFont", new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Component.accentColor", ACCENT);
    }

    /** The colour for a result tone, or {@code null} for the look-and-feel's default label colour. */
    public static Color color(Tone tone) {
        return switch (tone) {
            case POSITIVE -> POSITIVE;
            case CAUTION -> CAUTION;
            case NEGATIVE -> NEGATIVE;
            case PROGRESS -> ACCENT;
            case NEUTRAL -> null;
        };
    }
}
