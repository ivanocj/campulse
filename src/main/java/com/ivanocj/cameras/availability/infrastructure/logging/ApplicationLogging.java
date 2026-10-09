package com.ivanocj.cameras.availability.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.Duration;
import ch.qos.logback.core.util.FileSize;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

/** Desktop-owned logging lifecycle. Failure to initialize disk logging leaves a usable console/application. */
public final class ApplicationLogging implements AutoCloseable {
    private static final String APPLICATION_PACKAGE = "com.ivanocj.cameras.availability";
    private static final FileSize MAX_FILE_SIZE = new FileSize(10 * 1024 * 1024);
    private static final int RETAINED_FILES = 5;
    private final LoggerContext context;
    private final Logger logger;
    private final AtomicBoolean closed = new AtomicBoolean();
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean fileLoggingEnabled;
    private OperationalAsyncAppender asyncFile;
    private Thread.UncaughtExceptionHandler previousHandler;
    private Thread.UncaughtExceptionHandler handler;

    private ApplicationLogging(LoggerContext context) {
        this.context = context;
        this.logger = context.getLogger(ApplicationLogging.class);
    }

    /** Called once by the executable entry point, before opening Swing or starting any probes. */
    public static ApplicationLogging initialize() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var configuredLevel = System.getProperty("ppcs.log.level");
        var level = parseLevel(configuredLevel);
        Path directory = null;
        try {
            var configured = System.getProperty("ppcs.log.dir");
            directory = configured == null || configured.isBlank()
                    ? Path.of(System.getProperty("user.home"), ".ppcs-availability", "logs") : Path.of(configured);
        } catch (RuntimeException invalidDirectory) {
            // configure() will emit a safe warning and use the console instead.
        }
        var logging = configure(context, directory, level, MAX_FILE_SIZE, RETAINED_FILES);
        if (configuredLevel != null && !Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")
                .contains(configuredLevel.strip().toUpperCase(Locale.ROOT))) {
            logging.logger.warn("event=invalid_log_level default=INFO");
        }
        logging.installUncaughtExceptionHandler();
        Runtime.getRuntime().addShutdownHook(new Thread(logging::close, "logging-shutdown"));
        return logging;
    }

    static ApplicationLogging configure(LoggerContext context, Path directory, Level level,
                                        FileSize maxFileSize, int retainedFiles) {
        if (maxFileSize.getSize() < 1 || retainedFiles < 1 || retainedFiles > 20) {
            throw new IllegalArgumentException("Invalid log rotation limits.");
        }
        context.reset();
        context.start();
        var root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.WARN);
        context.getLogger(APPLICATION_PACKAGE).setLevel(level);
        var logging = new ApplicationLogging(context);
        var runId = UUID.randomUUID().toString();
        var console = new ConsoleAppender<ILoggingEvent>();
        console.setContext(context);
        console.setName("CONSOLE");
        console.setTarget("System.err");
        console.setEncoder(encoder(context, runId));
        var threshold = new ThresholdFilter();
        threshold.setLevel("WARN");
        threshold.start();
        console.addFilter(threshold);
        console.start();
        root.addAppender(console);
        context.getStatusManager().add(new SafeLoggingStatusListener(context, console));
        RollingFileAppender<ILoggingEvent> file = null;
        try {
            logging.openLogDirectory(directory);
            file = new RollingFileAppender<>();
            file.setContext(context);
            file.setName("FILE");
            file.setFile(directory.resolve("availability.log").toString());
            file.setAppend(true);
            file.setEncoder(encoder(context, runId));
            var rolling = new FixedWindowRollingPolicy();
            rolling.setContext(context);
            rolling.setParent(file);
            rolling.setFileNamePattern(directory.resolve("availability.%i.log.gz").toString());
            rolling.setMinIndex(1);
            rolling.setMaxIndex(retainedFiles);
            rolling.start();
            var triggering = new SizeBasedTriggeringPolicy<ILoggingEvent>();
            triggering.setContext(context);
            triggering.setMaxFileSize(maxFileSize);
            triggering.setCheckIncrement(Duration.buildByMilliseconds(0));
            triggering.start();
            file.setRollingPolicy(rolling);
            file.setTriggeringPolicy(triggering);
            file.start();
            if (!file.isStarted()) {
                file.stop();
                throw new IOException("File appender did not start.");
            }
            var async = new OperationalAsyncAppender();
            async.setContext(context);
            async.setName("ASYNC_FILE");
            async.setQueueSize(4096);
            // Discard only DEBUG/TRACE when nearly full. INFO/WARN/ERROR apply bounded-queue backpressure.
            async.setDiscardingThreshold(512);
            async.setNeverBlock(false);
            async.setMaxFlushTime(5000);
            async.addAppender(file);
            async.start();
            if (!async.isStarted()) { throw new IOException("Async appender did not start."); }
            root.addAppender(async);
            logging.asyncFile = async;
            logging.fileLoggingEnabled = true;
        } catch (IOException | RuntimeException unavailable) {
            if (file != null) { file.stop(); }
            logging.releaseLock();
            console.clearAllFilters();
            logging.logger.warn("event=file_logging_unavailable mode=console reason={}",
                    unavailable.getClass().getSimpleName());
        }
        logging.logger.info("event=logging_configured mode={} application_level={} max_file_bytes={} retained_files={}",
                logging.fileLoggingEnabled ? "file" : "console", level, maxFileSize.getSize(), retainedFiles);
        return logging;
    }

    private static LayoutWrappingEncoder<ILoggingEvent> encoder(LoggerContext context, String runId) {
        var layout = new PatternLayout();
        layout.setContext(context);
        layout.getInstanceConverterMap().put("safeEx", SafeThrowableConverter::new);
        layout.setPattern("%date{yyyy-MM-dd'T'HH:mm:ss.SSSXXX,UTC} %-5level [%thread] %logger{48} run="
                + runId + " batch=%X{batchId} probe=%X{probeId} line=%X{sourceLine} %msg%n%safeEx");
        layout.start();
        var encoder = new LayoutWrappingEncoder<ILoggingEvent>();
        encoder.setContext(context);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.setLayout(layout);
        encoder.start();
        return encoder;
    }

    private void openLogDirectory(Path directory) throws IOException {
        if (directory == null || Files.isSymbolicLink(directory)) {
            throw new IOException("Log directory unavailable.");
        }
        Files.createDirectories(directory, permissions(directory, "rwx------"));
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var actual = Files.getPosixFilePermissions(directory);
            if (!PosixFilePermissions.fromString("rwx------").containsAll(actual)) {
                throw new IOException("Log directory must be private to its owner.");
            }
        }
        lockChannel = FileChannel.open(directory.resolve("logging.lock"),
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                permissions(directory, "rw-------"));
        lock = lockChannel.tryLock();
        if (lock == null) { throw new IOException("Another instance owns the log files."); }
        var logFile = directory.resolve("availability.log");
        if (Files.isSymbolicLink(logFile)) { throw new IOException("Log file cannot be a symbolic link."); }
        for (int index = 1; index <= 20; index++) {
            if (Files.isSymbolicLink(directory.resolve("availability." + index + ".log.gz"))) {
                throw new IOException("Log archives cannot be symbolic links.");
            }
        }
        if (!Files.exists(logFile)) { Files.createFile(logFile, permissions(directory, "rw-------")); }
    }

    private static FileAttribute<?>[] permissions(Path path, String value) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(value))}
                : new FileAttribute<?>[0];
    }

    static Level parseLevel(String value) {
        if (value == null) { return Level.INFO; }
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "TRACE" -> Level.TRACE;
            case "DEBUG" -> Level.DEBUG;
            case "WARN" -> Level.WARN;
            case "ERROR" -> Level.ERROR;
            case "OFF" -> Level.OFF;
            default -> Level.INFO;
        };
    }

    public boolean fileLoggingEnabled() { return fileLoggingEnabled; }

    LoggerContext context() { return context; }

    public void installUncaughtExceptionHandler() {
        if (handler != null) { return; }
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        handler = (thread, failure) -> logger.error("event=uncaught_exception", failure);
        Thread.setDefaultUncaughtExceptionHandler(handler);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        if (asyncFile != null) { asyncFile.prepareForShutdown(); }
        logger.info("event=application_stopped");
        if (handler != null && Thread.getDefaultUncaughtExceptionHandler() == handler) {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        }
        try {
            context.stop(); // Drains the asynchronous appender, with a bounded shutdown wait.
        } finally {
            releaseLock();
        }
    }

    private void releaseLock() {
        try {
            if (lockChannel != null) { lockChannel.close(); }
        } catch (IOException failed) {
            logger.warn("event=logging_lock_release_failed reason={}", failed.getClass().getSimpleName());
        } finally {
            lock = null;
            lockChannel = null;
        }
    }
}
