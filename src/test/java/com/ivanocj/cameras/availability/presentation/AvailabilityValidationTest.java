package com.ivanocj.cameras.availability.presentation;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.configuration.DefaultServerConfigurationProvider;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter.FormInput;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter.InputField;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter.InvalidInput;
import com.ivanocj.cameras.availability.testsupport.DeviceFixtures;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Characterizes the original validation order/messages before field attribution is extracted. */
class AvailabilityValidationTest {
    private final AvailabilityPresenter presenter = new AvailabilityPresenter(new DefaultServerConfigurationProvider());

    @ParameterizedTest
    @ValueSource(strings = {"bad host", "127.0.0.1:0", "127.0.0.1:65536"})
    void serverErrorsTakePrecedenceOverAnInvalidIdentifier(String servers) {
        var expected = assertThrows(IllegalArgumentException.class, () -> presenter.request(input("FFFF-1-A", servers, false, TransportProtocol.PPCS)));
        var actual = assertThrows(IllegalArgumentException.class, () -> presenter.request(input("broken", servers, false, TransportProtocol.PPCS)));
        assertEquals(expected.getMessage(), actual.getMessage());
        assertEquals(servers.equals("bad host")
                ? "Enter IPv4 server hosts, optionally with :port, separated by commas."
                : "Server ports must be between 1 and 65535.", actual.getMessage());
    }

    @ParameterizedTest
    @EnumSource(value = TransportProtocol.class, names = {"PPCS", "XQ"})
    void blankLegacyServersFailBeforeIdentifierValidationOnlyWhenLanIsOff(TransportProtocol protocol) {
        var error = assertThrows(IllegalArgumentException.class, () -> presenter.request(input("broken", "", false, protocol)));
        assertEquals("Enter IPv4 server hosts, optionally with :port, separated by commas.", error.getMessage());
        var identifier = assertThrows(IllegalArgumentException.class, () -> presenter.request(input("broken", "", true, protocol)));
        assertEquals("Enter a DID in PREFIX-SERIAL-CODE format.", identifier.getMessage());
        assertTrue(presenter.request(input("FFFF-1-A", "", true, protocol)).servers().isEmpty());
    }

    @Test
    void anEncodedConfigurationDeterminesTheIdentifierLengthRule() {
        var encoded = new AvailabilityPresenter(new TestServerConfigurationProvider());
        String did = "ABCDEFG-4294967295-AB"; // 21 characters: valid PPCS, too long for XQ.
        assertEquals(TransportProtocol.PPCS, encoded.request(input(did, TestServerConfigurationProvider.SY_ENCODED, true, TransportProtocol.XQ)).protocol());
        var error = assertThrows(IllegalArgumentException.class, () -> encoded.request(input(did, DeviceFixtures.XQ_FFFF_CONFIGURATION, true, TransportProtocol.PPCS)));
        assertEquals("XQ DIDs must fit the 20-byte ASCII identity field.", error.getMessage());
    }

    @Test
    void anEncodedYooseeConflictTakesPrecedenceOverIdentifierValidation() {
        var encoded = new AvailabilityPresenter(new TestServerConfigurationProvider());
        var error = assertThrows(IllegalArgumentException.class, () -> encoded.request(input("broken", TestServerConfigurationProvider.SY_ENCODED, true, TransportProtocol.GWELL)));
        assertEquals("Encoded PPCS/XQ server configurations cannot select a Yoosee backend.", error.getMessage());
    }

    @Test
    void familySpecificValidationKeepsLeadingZerosAndCase() {
        assertEquals("001234", presenter.request(input(" 001234 ", "", true, TransportProtocol.GWELL)).identifier().value());
        assertEquals("Ab_01234", presenter.request(input(" Ab_01234 ", "", false, TransportProtocol.IOTVIDEO)).identifier().value());
        assertEquals("Enter a numeric GWell ID (preserve its leading zero).", assertThrows(IllegalArgumentException.class,
                () -> presenter.request(input("abc", "", true, TransportProtocol.GWELL))).getMessage());
        assertEquals("Enter a nonblank IoTVideo device ID without whitespace (up to 256 characters).", assertThrows(IllegalArgumentException.class,
                () -> presenter.request(input("Ab 01234", "", false, TransportProtocol.IOTVIDEO))).getMessage());
    }

    @Test
    void hiChipSerialParsingPrecedesTheGeneratedDidRules() {
        var error = assertThrows(IllegalArgumentException.class, () -> AvailabilityPresenter.hichipDid("1", "abc"));
        assertEquals("Enter a valid HiChip prefix and a numeric unsigned 32-bit serial. For input string: \"abc\"", error.getMessage());
        assertEquals("Enter a valid HiChip prefix and a numeric unsigned 32-bit serial. Enter a DID in PREFIX-SERIAL-CODE format.",
                assertThrows(IllegalArgumentException.class, () -> AvailabilityPresenter.hichipDid("1", "1")).getMessage());
        assertEquals("Enter a valid HiChip prefix and a numeric unsigned 32-bit serial. DID serial must fit an unsigned 32-bit integer.",
                assertThrows(IllegalArgumentException.class, () -> AvailabilityPresenter.hichipDid("FFFF", "4294967296")).getMessage());
        assertEquals("FFFF-158409-EBDAC", AvailabilityPresenter.hichipDid(" ffff ", "158409"));
    }

    @Test
    void requestFailuresCarryFieldAttributionWithoutChangingTheirMessages() {
        var servers = assertThrows(InvalidInput.class, () -> presenter.request(input("broken", "bad host", false, TransportProtocol.PPCS)));
        assertEquals(List.of(InputField.SERVERS), servers.fields());
        assertEquals(servers.getCause().getMessage(), servers.getMessage());
        var identifier = assertThrows(InvalidInput.class, () -> presenter.request(input("broken", "127.0.0.1", false, TransportProtocol.PPCS)));
        assertEquals(List.of(InputField.IDENTIFIER), identifier.fields());
        assertEquals(identifier.getCause().getMessage(), identifier.getMessage());
        for (var protocol : List.of(TransportProtocol.GWELL, TransportProtocol.IOTVIDEO)) {
            var invalid = assertThrows(InvalidInput.class, () -> presenter.request(input("", "", true, protocol)));
            assertEquals(List.of(InputField.IDENTIFIER), invalid.fields());
        }
    }

    @Test
    void encodedConflictsAreServerErrorsAndEffectiveXqLengthIsAnIdentifierError() {
        var encoded = new AvailabilityPresenter(new TestServerConfigurationProvider());
        assertEquals(List.of(InputField.SERVERS), assertThrows(InvalidInput.class, () -> encoded.request(input("broken",
                TestServerConfigurationProvider.SY_ENCODED, true, TransportProtocol.GWELL))).fields());
        assertEquals(List.of(InputField.IDENTIFIER), assertThrows(InvalidInput.class, () -> encoded.request(input("ABCDEFG-4294967295-AB",
                DeviceFixtures.XQ_FFFF_CONFIGURATION, true, TransportProtocol.PPCS))).fields());
    }

    @Test
    void hiChipAttributionReusesDomainRulesAndHandlesCombinedGeneratedDidFailures() {
        assertEquals(List.of(InputField.HICHIP_SERIAL), assertThrows(InvalidInput.class,
                () -> AvailabilityPresenter.hichipDid("1", "abc")).fields(), "Numeric parsing remains first");
        assertEquals(List.of(InputField.HICHIP_PREFIX), assertThrows(InvalidInput.class,
                () -> AvailabilityPresenter.hichipDid("1", "1")).fields());
        for (String serial : List.of("-1", "4294967296", "9223372036854775808")) {
            assertEquals(List.of(InputField.HICHIP_SERIAL), assertThrows(InvalidInput.class,
                    () -> AvailabilityPresenter.hichipDid("FFFF", serial)).fields());
        }
        assertEquals(List.of(InputField.HICHIP_PREFIX, InputField.HICHIP_SERIAL), assertThrows(InvalidInput.class,
                () -> AvailabilityPresenter.hichipDid("1", "-1")).fields());
        assertEquals(List.of(InputField.HICHIP_PREFIX, InputField.HICHIP_SERIAL), assertThrows(InvalidInput.class,
                () -> AvailabilityPresenter.hichipDid("ABCDEFG", "4294967295")).fields(), "Combined DID length must not blame only one valid field");
    }

    private static FormInput input(String did, String servers, boolean lan, TransportProtocol protocol) {
        return new FormInput(did, servers, 10, lan, protocol);
    }
}
