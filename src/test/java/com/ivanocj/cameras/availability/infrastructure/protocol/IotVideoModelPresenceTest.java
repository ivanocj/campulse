package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.application.model.RemotePresence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IotVideoModelPresenceTest {
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static RemotePresence parse(String value) throws IOException {
        return IotVideoModelPresence.parse(value.getBytes(StandardCharsets.UTF_8), NOW);
    }
    @Test void onlyTimestampedCurrentZeroAndOneArePresence() throws Exception {
        long stamp = NOW.getEpochSecond();
        assertEquals(RemotePresence.State.ONLINE, parse("{\"ProReadonly\":{\"_online\":{\"ctlVal\":1,\"stVal\":0,\"t\":" + stamp + "}}}").state());
        var offline = parse("{\"ProReadonly\":{\"_online\":{\"stVal\":0,\"t\":" + stamp + "}}}");
        assertEquals(RemotePresence.State.OFFLINE, offline.state());
        assertEquals(NOW, offline.reportedAt().orElseThrow());
        assertEquals(NOW, offline.receivedAt());
    }
    @Test void missingMalformedAndUntimestampedValuesRemainUnknown() throws Exception {
        for (var json : new String[]{"{}", "{\"ProReadonly\":{}}", "{\"ProReadonly\":{\"_online\":{\"stVal\":0}}}",
                "{\"ProReadonly\":{\"_online\":{\"stVal\":\"1\",\"t\":1}}}",
                "{\"ProReadonly\":{\"_online\":{\"stVal\":3,\"t\":1}}}"}) {
            assertEquals(RemotePresence.State.UNKNOWN, parse(json).state());
        }
    }
    @Test void staleAndFutureTimestampsAreNotFreshOfflineOrOnlineEvidence() throws Exception {
        assertEquals(RemotePresence.State.STALE, parse("{\"ProReadonly\":{\"_online\":{\"stVal\":0,\"t\":" + (NOW.getEpochSecond() - 121) + "}}}").state());
        assertEquals(RemotePresence.State.UNKNOWN, parse("{\"ProReadonly\":{\"_online\":{\"stVal\":1,\"t\":" + (NOW.getEpochSecond() + 6) + "}}}").state());
    }
    @Test void oversizedNumbersCannotWrapIntoOnlineOrOfflineValues() throws Exception {
        for (var value : new String[]{"18446744073709551616", "18446744073709551617", "-18446744073709551616"}) {
            assertEquals(RemotePresence.State.UNKNOWN, parse("{\"ProReadonly\":{\"_online\":{\"stVal\":"
                    + value + ",\"t\":" + NOW.getEpochSecond() + "}}}").state());
        }
    }
    @Test void rejectsDuplicateKeysTrailingDocumentsInvalidUtf8AndDeepObjectsWithoutEchoingModel() {
        for (var json : new String[]{"{\"secret\":\"NEVER_ECHO\",\"secret\":1}", "{}{}", "[".repeat(33) + "0" + "]".repeat(33)}) {
            var exception = assertThrows(IOException.class, () -> parse(json));
            assertFalse(exception.getMessage().contains("NEVER_ECHO"));
            assertNull(exception.getCause());
        }
        assertThrows(IOException.class, () -> IotVideoModelPresence.parse(new byte[]{'{', '"', (byte) 0xff, '"', ':', '0', '}'}, NOW));
    }
}
