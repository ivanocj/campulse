package com.ivanocj.cameras.availability.testsupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InMemoryPreferencesFactoryTest {
    private final InMemoryPreferencesFactory factory = new InMemoryPreferencesFactory();

    @Test
    void theTestJvmUsesMemoryPreferencesAndRepositoryLocalFiles(@TempDir Path directory) throws Exception {
        assertEquals(InMemoryPreferencesFactory.class.getName(),
                System.getProperty("java.util.prefs.PreferencesFactory"));
        assertSame(InMemoryPreferencesFactory.class, Preferences.userRoot().getClass().getEnclosingClass());
        assertSame(InMemoryPreferencesFactory.class, Preferences.systemRoot().getClass().getEnclosingClass());
        var repository = Path.of("").toRealPath();
        assertTrue(Path.of(System.getProperty("user.home")).toRealPath().startsWith(repository));
        assertTrue(Path.of(System.getProperty("java.io.tmpdir")).toRealPath().startsWith(repository));
        assertTrue(directory.toRealPath().startsWith(repository));
    }

    @Test
    void userAndSystemRootsAndIndependentFactoriesHaveSeparateValues() {
        assertSame(factory.userRoot(), factory.userRoot());
        assertNotSame(factory.userRoot(), factory.systemRoot());
        assertTrue(factory.userRoot().node("settings").isUserNode());
        assertFalse(factory.systemRoot().node("settings").isUserNode());
        factory.userRoot().put("did", "FFFF-1-A");
        assertNull(factory.systemRoot().get("did", null));
        assertNull(new InMemoryPreferencesFactory().userRoot().get("did", null));
    }

    @Test
    void valuesSurviveFlushAndSyncWithinTheJvm() throws Exception {
        var node = factory.userRoot().node("application/form");
        node.put("description", "Câmera entrada");
        node.putInt("timeout", 25);
        node.putBoolean("lanSearch", false);
        node.flush();
        node.sync();
        var sameNode = factory.userRoot().node("application/form");
        assertSame(node, sameNode);
        assertEquals("Câmera entrada", sameNode.get("description", ""));
        assertEquals(25, sameNode.getInt("timeout", 10));
        assertFalse(sameNode.getBoolean("lanSearch", true));
        node.remove("description");
        assertNull(node.get("description", null));
        assertEquals(2, node.keys().length);
    }

    @Test
    void removingANodeRemovesItsDescendantsAndRecreationStartsEmpty() throws Exception {
        var parent = factory.userRoot().node("application");
        var child = parent.node("form");
        child.node("nested").put("did", "FFFF-1-A");
        child.put("did", "WWWW-2-B");
        assertArrayEquals(new String[] {"form"}, parent.childrenNames());
        child.removeNode();
        assertArrayEquals(new String[0], parent.childrenNames());
        assertFalse(parent.nodeExists("form"));
        assertThrows(IllegalStateException.class, () -> child.put("did", "removed"));
        var recreated = parent.node("form");
        assertNotSame(child, recreated);
        assertNull(recreated.get("did", null));
        assertFalse(recreated.nodeExists("nested"));
    }
}
