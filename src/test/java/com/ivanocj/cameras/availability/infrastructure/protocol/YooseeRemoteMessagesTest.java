package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.application.model.RemotePresence;
import com.ivanocj.cameras.availability.application.model.YooseeAccount;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

class YooseeRemoteMessagesTest {
    @Test void certificationMatchesIndependentPythonHmacRc5AndOpenSslAesFixture() throws Exception {
        var token = new byte[64];
        var key = new byte[32];
        var nonce = new byte[12];
        for (int i = 0; i < token.length; i++) { token[i] = (byte) i; }
        token[0] = 1;
        for (int i = 0; i < key.length; i++) { key[i] = (byte) i; }
        for (int i = 0; i < nonce.length; i++) { nonce[i] = (byte) i; }
        try (var account = new YooseeAccount.IotVideo(9999, token, Optional.empty());
             var codec = new GuteFrameCodec(com.ivanocj.cameras.availability.domain.model.TransportProtocol.IOTVIDEO)) {
            var expected = HexFormat.of().parseHex(
                    "7f0ca400082938085a86209b25ad9f2fe8f9eed800004d01fa71f2f49322929c"
                    + "a7d706517f1180dac39aa044ceeb2615560b2272fcfc07e5e173d21999ed89500"
                    + "804000008070605040302010000000000000000010147660001020304050607"
                    + "08090a0b137ff76d2ec313f433dde7b14e39897c63d5ce5f6ee74e34cc9c82d0"
                    + "26c7a80a0669e8642e9326f5eda247073e1fa87c0c9815bfb0ab5ca655588adfa4bc5e93");
            assertArrayEquals(expected, IotVideoRemoteMessages.certify(account, key, nonce, 0x11223344,
                    0x0102030405060708L, codec));
        }
    }
    @Test void currentGwellAccountFieldsLandAtRecoveredWireOffsets() {
        try (var account = new YooseeAccount.Gwell(new GwellDeviceId("01234"), 11, 12, 21, 22, Optional.empty())) {
            var data = GwellRemoteMessages.certify(account, 9);
            assertEquals(0x800004d2L, i64(data, 4));
            assertEquals(21, i32(data, 24));
            assertEquals(22, i32(data, 28));
            assertEquals(11, i32(data, 32));
            assertEquals(12, i32(data, 36));
            assertEquals(44, data.length);
        }
    }
    @Test void gwellStatusRequiresExactCountsAndFullTargetIdentity() throws Exception {
        var data = frame(0x7f, 0x2e, 84, 1, 2, 34);
        buffer(data).putShort(28, (short) 1).putInt(32, 0x800004d2);
        data[40] = 2;
        assertEquals(RemotePresence.State.ONLINE, GwellRemoteMessages.presence(data, 0x800004d2));
        assertEquals(RemotePresence.State.UNKNOWN, GwellRemoteMessages.presence(data, 1234));
        data[40] = 0;
        assertEquals(RemotePresence.State.OFFLINE, GwellRemoteMessages.presence(data, 0x800004d2));
        data[28] = 2;
        assertThrows(IOException.class, () -> GwellRemoteMessages.presence(data, 0x800004d2));
    }
    @Test void gwellExplicitOfflineBucketIsNotInferredFromOmission() throws Exception {
        var data = frame(0x7f, 0x2e, 36, 1, 2, 34);
        buffer(data).putShort(30, (short) 1).putInt(32, 1234);
        assertEquals(RemotePresence.State.OFFLINE, GwellRemoteMessages.presence(data, 1234));
        assertEquals(RemotePresence.State.UNKNOWN, GwellRemoteMessages.presence(data, 99));
    }
    @Test void iotVideoUsesRegisteredCaseSensitiveMappingsAndStrictCompleteUnsignedDecimal() throws Exception {
        var mappings = Map.of("abcdefabcdefabcdefabcdefabcdefab", 1234L);
        assertEquals(1234, IotVideoRemoteMessages.resolveIdentity("abcdefabcdefabcdefabcdefabcdefab", mappings));
        assertEquals(-1L, IotVideoRemoteMessages.resolveIdentity("18446744073709551615", Map.of()));
        for (var invalid : new String[]{"123x", "0", "18446744073709551616", "ABCDEFABCDEFABCDEFABCDEFABCDEFABCD"}) {
            assertThrows(IOException.class, () -> IotVideoRemoteMessages.resolveIdentity(invalid, mappings));
        }
    }
    @Test void identityListsHaveBoundedCountsAndNoDuplicateAliases() throws Exception {
        var data = frame(0x7e, 0xa7, 60, 7, 2, 0);
        buffer(data).putShort(24, (short) 1).putShort(28, (short) 1).putLong(32, 1234);
        assertEquals(Map.of("1234", 1234L), IotVideoRemoteMessages.identities(data));
        data[28] = 2;
        assertThrows(IOException.class, () -> IotVideoRemoteMessages.identities(data));
    }
    @Test void modelBytesRequireMatchingDidErrorZeroExactLengthAndTerminator() throws Exception {
        var data = frame(0x7e, 0xb8, 43, 7, 2, 0);
        buffer(data).putLong(24, 1234).putShort(38, (short) 2);
        data[32] = 1;
        data[40] = '{'; data[41] = '}';
        assertArrayEquals(new byte[]{'{', '}'}, IotVideoRemoteMessages.modelJson(data, 1234));
        assertThrows(IOException.class, () -> IotVideoRemoteMessages.modelJson(data, 99));
        data[42] = 1;
        assertThrows(IOException.class, () -> IotVideoRemoteMessages.modelJson(data, 1234));
    }
}
