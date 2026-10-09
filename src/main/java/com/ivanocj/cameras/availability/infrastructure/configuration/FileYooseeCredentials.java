package com.ivanocj.cameras.availability.infrastructure.configuration;

import com.ivanocj.cameras.availability.application.model.YooseeAccount;
import com.ivanocj.cameras.availability.application.port.YooseeCredentialSource;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;

/** Reads user-supplied properties per check; never saves secrets or includes their contents/path in errors. */
public final class FileYooseeCredentials implements YooseeCredentialSource {
    private static final int MAX_BYTES = 16_384;
    private volatile Optional<Path> path;

    public FileYooseeCredentials(Optional<Path> path) { this.path = path; }
    public void select(Path path) { this.path = Optional.of(path); }
    public void clear() { this.path = Optional.empty(); }

    @Override public Optional<YooseeAccount> load(TransportProtocol protocol) throws IOException {
        var selected = path;
        if (selected.isEmpty()) { return Optional.empty(); }
        byte[] bytes;
        try {
            var file = selected.orElseThrow();
            var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > MAX_BYTES) { throw new IOException(); }
            try (var input = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
        } catch (IOException failed) {
            throw new IOException("Could not read the selected Yoosee credentials file.");
        }
        var properties = new Properties();
        try {
            if (bytes.length > MAX_BYTES) { throw new IllegalArgumentException(); }
            properties.load(new java.io.StringReader(new String(bytes, StandardCharsets.UTF_8)));
            String prefix = protocol == TransportProtocol.GWELL ? "gwell." : "iotvideo.";
            if (properties.stringPropertyNames().stream().noneMatch(name -> name.startsWith(prefix))) {
                return Optional.empty();
            }
            var expires = Optional.ofNullable(properties.getProperty(prefix + "expiresAt")).map(String::strip).map(Instant::parse);
            return switch (protocol) {
                case GWELL -> Optional.of(new YooseeAccount.Gwell(new GwellDeviceId(required(properties, "gwell.userId")),
                        word(properties, "gwell.sessionId1"), word(properties, "gwell.sessionId2"),
                        word(properties, "gwell.code1"), word(properties, "gwell.code2"), expires));
                case IOTVIDEO -> {
                    var token = HexFormat.of().parseHex(required(properties, "iotvideo.accessToken"));
                    try {
                        yield Optional.of(new YooseeAccount.IotVideo(Long.parseUnsignedLong(required(properties, "iotvideo.accessId")),
                                token, expires));
                    } finally { Arrays.fill(token, (byte) 0); }
                }
                default -> throw new IllegalArgumentException();
            };
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new IOException("Invalid Yoosee credential fields. Check the documented backend-specific account format.");
        } finally {
            Arrays.fill(bytes, (byte) 0);
            properties.clear();
        }
    }

    private static String required(Properties values, String field) {
        var value = values.getProperty(field);
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(); }
        return value.strip();
    }

    private static int word(Properties values, String field) {
        long value = Long.parseLong(required(values, field));
        if (value < Integer.MIN_VALUE || value > 0xffffffffL) { throw new IllegalArgumentException(); }
        return (int) value;
    }
}
