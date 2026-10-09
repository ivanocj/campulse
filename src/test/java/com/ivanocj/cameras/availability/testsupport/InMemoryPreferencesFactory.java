package com.ivanocj.cameras.availability.testsupport;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/** Test-JVM store: exercises Preferences without touching the platform's user or system settings. */
public final class InMemoryPreferencesFactory implements PreferencesFactory {
    private final Preferences user = new MemoryPreferences(null, "", true);
    private final Preferences system = new MemoryPreferences(null, "", false);

    @Override
    public Preferences userRoot() {
        return user;
    }

    @Override
    public Preferences systemRoot() {
        return system;
    }

    private static final class MemoryPreferences extends AbstractPreferences {
        // AbstractPreferences holds this node's lock when invoking the value SPI methods.
        private final Map<String, String> values = new HashMap<>();
        // Removing a child also removes it from its parent's backing-store directory.
        private final Map<String, MemoryPreferences> children = new ConcurrentHashMap<>();
        private final boolean user;

        MemoryPreferences(MemoryPreferences parent, String name, boolean user) {
            super(parent, name);
            this.user = user;
            newNode = parent != null;
        }

        @Override
        public boolean isUserNode() {
            return user;
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
            values.clear();
            children.clear();
            if (parent() instanceof MemoryPreferences parent) {
                parent.children.remove(name(), this);
            }
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return children.keySet().toArray(String[]::new);
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, child -> new MemoryPreferences(this, child, user));
        }

        @Override
        protected void syncSpi() {
            // Values are already in the only backing store: this test JVM's memory.
        }

        @Override
        protected void flushSpi() {
            // No platform Preferences files or registry writes.
        }
    }
}
