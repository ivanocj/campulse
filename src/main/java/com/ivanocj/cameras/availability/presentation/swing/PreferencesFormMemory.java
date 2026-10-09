package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link FormMemory} backed by the Java Preferences API. */
public final class PreferencesFormMemory implements FormMemory {
    private static final Logger LOG = LoggerFactory.getLogger(PreferencesFormMemory.class);
    private final Preferences node;

    public PreferencesFormMemory(Preferences node) {
        this.node = node;
    }

    @Override
    public Optional<FormState> load() {
        try {
            return readState();
        } catch (IllegalStateException | SecurityException failed) {
            LOG.warn("event=settings_load_failed", failed);
            return Optional.empty();
        }
    }

    private Optional<FormState> readState() {
        String did = node.get("did", null);
        String servers = node.get("servers", null);
        if (did == null || servers == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new FormState(did, node.get("profile", ""), servers,
                    TransportProtocol.valueOf(node.get("protocol", TransportProtocol.PPCS.name())),
                    node.getInt("timeoutSeconds", 10), node.getBoolean("lanSearch", true)));
        } catch (IllegalArgumentException corrupted) {
            LOG.warn("event=settings_invalid");
            return Optional.empty();
        }
    }

    @Override
    public void save(FormState state) {
        try {
            node.put("did", state.did());
            node.put("profile", state.profile());
            node.put("servers", state.servers());
            node.put("protocol", state.protocol().name());
            node.putInt("timeoutSeconds", state.timeoutSeconds());
            node.putBoolean("lanSearch", state.lanSearch());
            node.flush();
        } catch (BackingStoreException | IllegalStateException | SecurityException failed) {
            LOG.warn("event=settings_save_failed", failed);
            // Remembering the form is a convenience; the platform persists the values later or not at all.
        }
    }
}
