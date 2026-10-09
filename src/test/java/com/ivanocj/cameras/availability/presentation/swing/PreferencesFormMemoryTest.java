package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.presentation.swing.FormMemory.FormState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ivanocj.cameras.availability.testsupport.LogCapture;
import java.util.prefs.BackingStoreException;
import java.util.Optional;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PreferencesFormMemoryTest {
    private final Preferences node = Preferences.userRoot().node("ppcs-availability-test-" + UUID.randomUUID());

    @AfterEach
    void removeNode() throws Exception {
        node.removeNode();
    }

    @Test
    void roundTripsTheSubmittedForm() {
        var state = new FormState("FFFF-158409-EBDAC", "HIP2P · XQ FFFF/HHHH/GGGG", "127.0.0.1:32100",
                TransportProtocol.XQ, 15, false);
        var memory = new PreferencesFormMemory(node);
        memory.save(state);
        assertEquals(Optional.of(state), memory.load());
    }

    @Test
    void startsEmptyAndIgnoresCorruptedValues() {
        var memory = new PreferencesFormMemory(node);
        assertEquals(Optional.empty(), memory.load());
        node.put("did", "FFFF-158409-EBDAC");
        node.put("servers", "127.0.0.1");
        node.put("protocol", "CARRIER_PIGEON");
        assertEquals(Optional.empty(), memory.load());
    }

    @Test
    void settingsFailuresAreLoggedWithoutPreventingProbesOrExposingSavedValues() throws Exception {
        var preferences = mock(Preferences.class);
        var memory = new PreferencesFormMemory(preferences);
        when(preferences.get("did", null)).thenThrow(new SecurityException("private device details"));
        doThrow(new BackingStoreException("private settings path")).when(preferences).flush();
        try (var logs = new LogCapture()) {
            assertEquals(Optional.empty(), memory.load());
            assertDoesNotThrow(() -> memory.save(new FormState("FFFF-1-A", "profile", "198.51.100.1",
                    TransportProtocol.XQ, 10, false)));
            assertEquals(1, logs.events("settings_load_failed").size());
            assertEquals(1, logs.events("settings_save_failed").size());
        }
    }
}
