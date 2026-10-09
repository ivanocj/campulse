package com.ivanocj.cameras.availability.testsupport;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;

/** Isolated, childless in-memory node with controllable read/write/flush hooks. Never uses platform storage. */
public final class PreferencesTestStore extends AbstractPreferences {
    private final Map<String, String> values = new HashMap<>();
    private final Consumer<String> beforeGet;
    private final Consumer<String> beforePut;
    private final FlushAction beforeFlush;

    public PreferencesTestStore(Consumer<String> beforeGet, Consumer<String> beforePut, FlushAction beforeFlush) {
        super(null, "");
        this.beforeGet = Objects.requireNonNull(beforeGet);
        this.beforePut = Objects.requireNonNull(beforePut);
        this.beforeFlush = Objects.requireNonNull(beforeFlush);
    }

    @FunctionalInterface
    public interface FlushAction {
        void run() throws BackingStoreException;
    }

    @Override public boolean isUserNode() { return true; }
    @Override protected void putSpi(String key, String value) { beforePut.accept(key); values.put(key, value); }
    @Override protected String getSpi(String key) { beforeGet.accept(key); return values.get(key); }
    @Override protected void removeSpi(String key) { values.remove(key); }
    @Override protected void removeNodeSpi() { values.clear(); }
    @Override protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
    @Override protected String[] childrenNamesSpi() { return new String[0]; }
    @Override protected AbstractPreferences childSpi(String name) { throw new UnsupportedOperationException("This test node has no children."); }
    @Override protected void syncSpi() { }
    @Override protected void flushSpi() throws BackingStoreException { beforeFlush.run(); }
}
