package com.ivanocj.cameras.availability.infrastructure.configuration;

import com.ivanocj.cameras.availability.application.model.YooseeAccount;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileYooseeCredentialsTest {
    @TempDir Path directory;
    @Test void signedAndUnsignedLoginWordsMapWithoutEmbeddingCredentialsInDiagnostics() throws Exception {
        var file = directory.resolve("account.properties");
        Files.writeString(file, "gwell.userId=01234\ngwell.sessionId1=4294967295\ngwell.sessionId2=-2147483648\ngwell.code1=11\ngwell.code2=22\n");
        var source = new FileYooseeCredentials(Optional.of(file));
        try (var account = (YooseeAccount.Gwell) source.load(TransportProtocol.GWELL).orElseThrow()) {
            assertEquals(0x800004d2L, account.userId());
            assertEquals(-1, account.sessionId1());
            assertEquals(Integer.MIN_VALUE, account.sessionId2());
            assertEquals("GwellAccount[redacted]", account.toString());
        }
        assertTrue(source.load(TransportProtocol.IOTVIDEO).isEmpty());
        source.clear();
        assertTrue(source.load(TransportProtocol.GWELL).isEmpty());
    }
    @Test void tokenNamespaceIsAppliedAndArraysAreCopiedAndErased() {
        var token = new byte[64]; token[0] = 1; token[1] = 6;
        var account = new YooseeAccount.IotVideo(0x1fffffff12345678L, token, Optional.of(Instant.MAX));
        token[1] = 0;
        assertEquals(0x03ffffff12345678L, account.wireId());
        var copy = account.token(); copy[1] = 0;
        assertEquals(6, account.token()[1]);
        assertEquals("IotVideoAccount[redacted]", account.toString());
        account.close();
        assertThrows(IllegalStateException.class, account::token);
    }
    @Test void rejectsOversizeBrokenAndAnonymousCredentialsWithoutLeakingContentsOrPath() throws Exception {
        var file = directory.resolve("NEVER_ECHO.properties");
        var source = new FileYooseeCredentials(Optional.of(file));
        for (var input : new String[]{"iotvideo.accessId=1\niotvideo.accessToken=NEVER_ECHO", "x=" + "NEVER_ECHO".repeat(2000),
                "gwell.userId=1\ngwell.sessionId1=4294967296\ngwell.sessionId2=1\ngwell.code1=1\ngwell.code2=1"}) {
            Files.writeString(file, input);
            var failure = assertThrows(IOException.class, () -> source.load(input.startsWith("gwell") ? TransportProtocol.GWELL : TransportProtocol.IOTVIDEO));
            assertFalse(failure.getMessage().contains("NEVER_ECHO"));
            assertNull(failure.getCause());
        }
        assertThrows(IllegalArgumentException.class, () -> new YooseeAccount.IotVideo(1, new byte[64], Optional.empty()));
    }
    @Test void yooseeDefaultPortsNeverBecomeLegacy32100AndExplicitPortsArePreserved() {
        var provider = new DefaultServerConfigurationProvider();
        assertEquals(51701, provider.parse("host.example", TransportProtocol.GWELL).servers().getFirst().getPort());
        assertEquals(1234, provider.parse("host.example:1234", TransportProtocol.IOTVIDEO).servers().getFirst().getPort());
        assertEquals(32100, provider.parse("host.example", TransportProtocol.PPCS).servers().getFirst().getPort());
        assertThrows(IllegalArgumentException.class, () -> provider.parse("host.example:0", TransportProtocol.IOTVIDEO));
    }

    @Test void selectingAnotherRegularFileAndReloadingNeverCachesOldAccountSecrets() throws Exception {
        var file = directory.resolve("account.properties");
        var source = new FileYooseeCredentials(Optional.empty());
        var token = new byte[64]; token[0] = 1;
        Files.writeString(file, "iotvideo.accessId=18446744073709551615\niotvideo.accessToken="
                + HexFormat.of().formatHex(token) + "\niotvideo.expiresAt=2026-10-08T10:00:00Z\n");
        source.select(file);
        try (var account = (YooseeAccount.IotVideo) source.load(TransportProtocol.IOTVIDEO).orElseThrow()) {
            assertEquals(0xe0ffffffffffffffL, account.wireId());
            assertEquals(Optional.of(Instant.parse("2026-10-08T10:00:00Z")), account.expiresAt());
        }
        Files.writeString(file, "iotvideo.accessId=1\niotvideo.accessToken=" + HexFormat.of().formatHex(token));
        try (var account = (YooseeAccount.IotVideo) source.load(TransportProtocol.IOTVIDEO).orElseThrow()) {
            assertEquals(1, account.wireId());
        }
        source.clear();
        assertTrue(source.load(TransportProtocol.IOTVIDEO).isEmpty());
    }

    @Test void directoriesSymlinksAndMalformedExpiriesFailWithRedactedDiagnostics() throws Exception {
        var file = directory.resolve("PRIVATE_NAME.properties");
        var source = new FileYooseeCredentials(Optional.of(directory));
        var error = assertThrows(IOException.class, () -> source.load(TransportProtocol.GWELL));
        assertNull(error.getCause());
        Files.writeString(file, "gwell.expiresAt=PRIVATE_SECRET");
        var link = Files.createSymbolicLink(directory.resolve("linked.properties"), file);
        source.select(link);
        assertThrows(IOException.class, () -> source.load(TransportProtocol.GWELL));
        source.select(file);
        error = assertThrows(IOException.class, () -> source.load(TransportProtocol.GWELL));
        assertFalse(error.getMessage().contains("PRIVATE_"));
        assertNull(error.getCause());
    }
}
