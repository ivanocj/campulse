package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ivanocj.cameras.availability.application.model.RemotePresence;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Only an explicitly timestamped, current _online value is positive/negative presence evidence. */
public final class IotVideoModelPresence {
    public static final Duration MAX_AGE = Duration.ofMinutes(2);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(32767)
                    .maxNumberLength(32).maxDocumentLength(32767).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private IotVideoModelPresence() { }

    public static RemotePresence parse(byte[] json, Instant receivedAt) throws IOException {
        if (json.length > 32767) { throw new IOException("IoTVideo model exceeded the bounded size."); }
        try {
            var root = JSON.readTree(json);
            var property = root == null ? null : root.path("ProReadonly").path("_online");
            if (property == null || !property.isObject()) { return evidence(RemotePresence.State.UNKNOWN, Optional.empty(), receivedAt); }
            var value = property.has("ctlVal") ? property.path("ctlVal") : property.path("stVal");
            if (!value.isIntegralNumber() || !value.canConvertToInt() || (value.intValue() != 0 && value.intValue() != 1)) {
                return evidence(RemotePresence.State.UNKNOWN, Optional.empty(), receivedAt);
            }
            var time = property.path("t");
            if (!time.isIntegralNumber() || !time.canConvertToLong() || time.longValue() <= 0) {
                return evidence(RemotePresence.State.UNKNOWN, Optional.empty(), receivedAt);
            }
            var reported = Instant.ofEpochSecond(time.longValue());
            var age = Duration.between(reported, receivedAt);
            if (age.isNegative() && reported.isAfter(receivedAt.plusSeconds(5))) {
                return evidence(RemotePresence.State.UNKNOWN, Optional.of(reported), receivedAt);
            }
            return evidence(age.compareTo(MAX_AGE) > 0 ? RemotePresence.State.STALE
                    : value.longValue() == 1 ? RemotePresence.State.ONLINE : RemotePresence.State.OFFLINE,
                    Optional.of(reported), receivedAt);
        } catch (IOException | DateTimeException invalid) {
            // Jackson errors may contain model contents; never expose them to logging/UI exception chains.
            throw new IOException("Invalid bounded IoTVideo model JSON.");
        }
    }
    private static RemotePresence evidence(RemotePresence.State state, Optional<Instant> reported, Instant received) {
        return new RemotePresence(state, TransportProtocol.IOTVIDEO, RemotePresence.Source.IOTVIDEO_MODEL, reported, received);
    }
}
