package com.ivanocj.cameras.availability.infrastructure.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.util.FileSize;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationLoggingTest {
    @TempDir
    Path directory;
    private PrintStream previousConsole;
    private PrintStream testConsole;

    @BeforeEach
    void captureExpectedConsoleWarnings() {
        previousConsole = System.err;
        testConsole = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        System.setErr(testConsole);
    }

    @AfterEach
    void restoreConsole() {
        System.setErr(previousConsole);
        testConsole.close();
    }

    @Test
    void writesUtf8LevelledUtcEventsAndSnapshotsContextBeforeAsyncFlush() throws Exception {
        var context = context();
        var logging = ApplicationLogging.configure(context, directory, Level.INFO, new FileSize(10 * 1024 * 1024), 5);
        assertTrue(logging.fileLoggingEnabled());
        var logger = context.getLogger("com.ivanocj.cameras.availability.test");
        context.getMDCAdapter().put("batchId", "batch-correlation");
        context.getMDCAdapter().put("probeId", "probe-correlation");
        logger.debug("event=debug_should_be_filtered");
        logger.info("event=probe_completed protocol=XQ status=TIMED_OUT note=✓");
        context.getMDCAdapter().clear();
        context.getLogger("third.party.library").info("event=library_info_should_be_filtered");
        logging.close();
        logging.close();
        var text = Files.readString(directory.resolve("availability.log"));
        assertTrue(text.contains("event=probe_completed protocol=XQ status=TIMED_OUT note=✓"), text);
        assertTrue(text.matches("(?s).*\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z.*"), text);
        assertTrue(text.contains("batch=batch-correlation probe=probe-correlation"), text);
        assertTrue(text.contains("run="), text);
        assertFalse(text.contains("debug_should_be_filtered"));
        assertFalse(text.contains("library_info_should_be_filtered"));
        assertEquals(1, text.split("event=application_stopped", -1).length - 1);
    }

    @Test
    void rotatesBySizeCompressesArchivesAndRetainsOnlyTheConfiguredBackups() throws Exception {
        var context = context();
        try (var logging = ApplicationLogging.configure(context, directory, Level.DEBUG, new FileSize(1024), 2)) {
            var logger = context.getLogger("com.ivanocj.cameras.availability.test");
            for (int index = 0; index < 200; index++) {
                logger.debug("event=rotation_test index={} payload={}", index, "x".repeat(250));
            }
        }
        try (var files = Files.list(directory)) {
            assertEquals(2, files.filter(path -> path.getFileName().toString().endsWith(".gz")).count());
        }
        for (int index = 1; index <= 2; index++) {
            try (var zipped = new GZIPInputStream(Files.newInputStream(directory.resolve("availability." + index + ".log.gz")))) {
                assertTrue(new String(zipped.readAllBytes(), StandardCharsets.UTF_8).contains("event=rotation_test"));
            }
        }
        assertTrue(Files.size(directory.resolve("availability.log")) < 2048);
    }

    @Test
    void anUnwritableDirectoryFallsBackToConsoleWithoutBreakingTheApplication() throws Exception {
        var blocker = Files.writeString(directory.resolve("not-a-directory"), "leave this untouched");
        var captured = new ByteArrayOutputStream();
        var previous = System.err;
        try (var stderr = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setErr(stderr);
            try (var logging = ApplicationLogging.configure(context(), blocker, Level.INFO, new FileSize(1024), 2)) {
                assertFalse(logging.fileLoggingEnabled());
                logging.context().getLogger("com.ivanocj.cameras.availability.test").info("event=still_running");
            }
        } finally {
            System.setErr(previous);
        }
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("event=file_logging_unavailable"));
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("event=still_running"));
        assertEquals("leave this untouched", Files.readString(blocker));
    }

    @Test
    void concurrentInstancesNeverRotateTheSameFilesAndTheLockIsReleasedOnClose() throws Exception {
        try (var first = ApplicationLogging.configure(context(), directory, Level.INFO, new FileSize(1024), 2);
             var second = ApplicationLogging.configure(context(), directory, Level.INFO, new FileSize(1024), 2)) {
            assertTrue(first.fileLoggingEnabled());
            assertFalse(second.fileLoggingEnabled());
        }
        try (var next = ApplicationLogging.configure(context(), directory, Level.INFO, new FileSize(1024), 2)) {
            assertTrue(next.fileLoggingEnabled());
        }
    }

    @Test
    void uncaughtExceptionsKeepStackFramesButNeverPersistRawMessagesAndRestoreThePreviousHandler() throws Exception {
        var previous = Thread.getDefaultUncaughtExceptionHandler();
        try (var logging = ApplicationLogging.configure(context(), directory, Level.INFO, new FileSize(1024 * 1024), 2)) {
            logging.installUncaughtExceptionHandler();
            var failure = new IllegalStateException("FFFF-1-A 198.51.100.7 token=secret\nforged log line",
                    new java.io.IOException("sensitive description"));
            failure.addSuppressed(new java.io.IOException("another secret"));
            var thread = new Thread(() -> { throw failure; }, "offline-crash-test");
            thread.start();
            thread.join(2000);
            assertFalse(thread.isAlive());
        } finally {
            assertSame(previous, Thread.getDefaultUncaughtExceptionHandler());
        }
        var text = Files.readString(directory.resolve("availability.log"));
        assertTrue(text.contains("event=uncaught_exception"), text);
        assertTrue(text.contains("java.lang.IllegalStateException"), text);
        assertTrue(text.contains("java.io.IOException"), text);
        assertTrue(text.contains("ApplicationLoggingTest"), text);
        for (var sensitive : new String[] {"FFFF-1-A", "198.51.100.7", "secret", "sensitive description", "forged log line"}) {
            assertFalse(text.contains(sensitive), text);
        }
    }

    @Test
    void invalidLevelsUseSafeDefaultsAndDoNotInterpretArbitraryConfiguration() {
        assertEquals(Level.INFO, ApplicationLogging.parseLevel(null));
        assertEquals(Level.INFO, ApplicationLogging.parseLevel("bad\ninjected"));
        assertEquals(Level.DEBUG, ApplicationLogging.parseLevel(" debug "));
        assertEquals(Level.WARN, ApplicationLogging.parseLevel("WARN"));
        assertEquals(Level.OFF, ApplicationLogging.parseLevel("OFF"));
    }

    @Test
    void aSharedDirectoryIsNotSilentlyModifiedAndSymlinksCannotRedirectLoggingIntoOtherFiles() throws Exception {
        if (!directory.getFileSystem().supportedFileAttributeViews().contains("posix")) { return; }
        var shared = Files.createDirectory(directory.resolve("shared"));
        var permissions = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(shared, permissions);
        try (var logging = ApplicationLogging.configure(context(), shared, Level.INFO, new FileSize(1024), 2)) {
            assertFalse(logging.fileLoggingEnabled());
            assertEquals(permissions, Files.getPosixFilePermissions(shared));
        }
        var privateFolder = Files.createDirectory(directory.resolve("private"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var unrelated = Files.writeString(directory.resolve("unrelated"), "untouched");
        Files.createSymbolicLink(privateFolder.resolve("availability.1.log.gz"), unrelated);
        try (var logging = ApplicationLogging.configure(context(), privateFolder, Level.INFO, new FileSize(1024), 2)) {
            assertFalse(logging.fileLoggingEnabled());
            assertEquals("untouched", Files.readString(unrelated));
        }
    }

    private static LoggerContext context() {
        var context = new LoggerContext();
        context.setName("logging-test-" + UUID.randomUUID());
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.start();
        return context;
    }
}
