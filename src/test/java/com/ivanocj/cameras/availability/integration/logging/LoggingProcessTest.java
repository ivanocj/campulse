package com.ivanocj.cameras.availability.integration.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ivanocj.cameras.availability.testsupport.InMemoryPreferencesFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoggingProcessTest {
    @TempDir
    Path directory;

    @Test
    void executableStartupCreatesDefaultUserLogsAndTheShutdownHookFlushesThem() throws Exception {
        var home = Files.createDirectory(directory.resolve("home"));
        var output = run(List.of("-Duser.home=" + home), "minimal");
        var file = home.resolve(".ppcs-availability/logs/availability.log");
        var text = Files.readString(file);
        assertTrue(text.contains("event=logging_configured"));
        assertTrue(text.contains("event=fixture_started"));
        assertTrue(text.contains("event=application_stopped"));
        assertFalse(output.contains("SLF4J"), output);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        }
    }

    @Test
    void debugLogsKeepAllConcurrentOutcomesAndProtocolDiagnosticsWithoutPersistingDeviceData() throws Exception {
        var logs = directory.resolve("debug-logs");
        run(List.of("-Dppcs.log.dir=" + logs, "-Dppcs.log.level=DEBUG"), "exercise");
        var text = Files.readString(logs.resolve("availability.log"));
        assertEquals(100, text.split("event=probe_started", -1).length - 1);
        assertEquals(100, text.split("event=probe_completed", -1).length - 1);
        assertEquals(100, text.split("event=udp_session_closed", -1).length - 1);
        assertTrue(text.contains("event=batch_completed rows=100 checked=100"), text);
        assertTrue(text.contains("malformed_packets=1"));
        assertTrue(text.contains("line=2"));
        assertTrue(text.contains("protocol=XQ"));
        assertTrue(text.contains("protocol=PPCS"));
        assertTrue(text.contains("event=application_stopped"));
        for (var sensitive : new String[] {"FFFF-", "WWWW-", "private-description-secret", "198.51.100.", "127.0.0.1"}) {
            assertFalse(text.contains(sensitive), "Unexpected device data in persisted logs: " + sensitive);
        }
    }

    @Test
    void invalidDirectoryAndLogLevelUseSafeConsoleFallbackAndStillExitNormally() throws Exception {
        var blocker = Files.writeString(directory.resolve("blocker"), "unchanged");
        var output = run(List.of("-Dppcs.log.dir=" + blocker, "-Dppcs.log.level=INVALID"), "minimal");
        assertTrue(output.contains("event=file_logging_unavailable"), output);
        assertTrue(output.contains("event=invalid_log_level"), output);
        assertTrue(output.contains("event=fixture_started"), output);
        assertTrue(output.contains("event=application_stopped"), output);
        assertEquals("unchanged", Files.readString(blocker));
    }

    private String run(List<String> properties, String scenario) throws Exception {
        var command = new ArrayList<String>();
        var java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        command.add(java.toString());
        command.add("-Duser.home=" + directory);
        command.add("-Djava.io.tmpdir=" + directory);
        command.add("-Djava.util.prefs.PreferencesFactory=" + InMemoryPreferencesFactory.class.getName());
        command.addAll(properties);
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(LoggingProcessFixture.class.getName());
        command.add(scenario);
        var output = directory.resolve("child-output.txt");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Logging child process did not finish");
            assertEquals(0, process.exitValue(), Files.readString(output));
            return Files.readString(output);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); }
        }
    }
}
