package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.io.IOException;
import java.util.HexFormat;
import java.util.Optional;
import com.ivanocj.cameras.availability.application.model.YooseeAccount;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

class GuteFrameCodecTest {
    @Test void gwellModeOneInitMatchesIndependentFixtureWithNativeVerificationTrailer() throws Exception {
        try (var codec = new GuteFrameCodec(TransportProtocol.GWELL);
             var account = new YooseeAccount.Gwell(new GwellDeviceId("01234"), 11, 12, 21, 22, Optional.empty())) {
            var expected = HexFormat.of().parseHex("7f81500088da1988cf3412ef52d18e6ec684734b05010000"
                    + "db12115d4655e09381a92962ca163d294cad192abd0dcf194cad192abd0dcf19"
                    + "4cad192abd0dcf194cad192abd0dcf19ecd073f0e13b834f");
            assertArrayEquals(expected, codec.encode(GwellRemoteMessages.initInfo(account, 0x11223344, 1)));
        }
    }
    @Test void matchesIndependentPythonGwellListFixtureWithNativeTwelveByteBootstrapKey() throws Exception {
        try (var codec = new GuteFrameCodec(TransportProtocol.GWELL)) {
            var expected = HexFormat.of().parseHex("7f2b240088da1988cf3412efdba034bacf34fa6c01000000b1bba3ee14dc933000000000");
            assertArrayEquals(expected, codec.encode(GwellRemoteMessages.list(0x800004d2L)));
            assertEquals(0x800004d2L, i64(codec.decode(expected), 4));
        }
    }
    @Test void matchesIndependentPythonIotVideoListFixtureIncludingIdentityXor() throws Exception {
        try (var codec = new GuteFrameCodec(TransportProtocol.IOTVIDEO)) {
            var expected = HexFormat.of().parseHex("7f152800e99f0778d9fd3a348bc74b267c14f21e00000100b403bcb04c02f4141b543c610e1bce3b");
            assertArrayEquals(expected, codec.encode(IotVideoRemoteMessages.list(0x1111222233334444L)));
            assertEquals(0x1111222233334444L, i64(codec.decode(expected), 4));
        }
    }
    @Test void verifiesSixRoundFixturesAtBothNativeWordWidths() {
        var key = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f");
        for (int width : new int[]{8, 16}) {
            var block = new byte[width];
            for (int i = 0; i < width; i++) { block[i] = (byte) i; }
            try (var rc5 = new YooseeRc5(width, 6, key)) {
                rc5.encrypt(block, 0);
                assertEquals(width == 8 ? "85ddcda322ec3856" : "fbebdaf521ccf6abfa44945ca48c0614", HexFormat.of().formatHex(block));
            }
        }
    }
    @Test void negotiatedFramesPreserveNonBlockAlignedTailsAndResponseCorrelation() throws Exception {
        for (var protocol : new TransportProtocol[]{TransportProtocol.GWELL, TransportProtocol.IOTVIDEO}) {
            try (var codec = new GuteFrameCodec(protocol)) {
                int flags = protocol == TransportProtocol.GWELL ? 2 | 32 : (2 << 16) | (1 << 21);
                var plain = frame(protocol == TransportProtocol.GWELL ? 0x7f : 0x7e, 6, 39, 1234, 7, flags);
                for (int i = 24; i < plain.length; i++) { plain[i] = (byte) i; }
                buffer(plain).putInt(16, 0x12345678);
                assertThrows(IOException.class, () -> codec.encode(plain));
                codec.negotiate(new byte[32]);
                assertArrayEquals(plain, codec.decode(codec.encode(plain)));
            }
        }
    }
    @Test void rejectsWrongLengthInvalidModesTruncatedTrailersAndCorruptedChecksums() throws Exception {
        for (var protocol : new TransportProtocol[]{TransportProtocol.GWELL, TransportProtocol.IOTVIDEO}) {
            try (var codec = new GuteFrameCodec(protocol)) {
                var plain = frame(0x7f, 3, 32, 1, 2, protocol == TransportProtocol.GWELL ? 1 : 1 << 16);
                var corrupt = codec.encode(plain);
                corrupt[12] ^= 32;
                assertThrows(IOException.class, () -> codec.decode(corrupt));
                plain[2] = 31;
                assertThrows(IOException.class, () -> codec.encode(plain));
                plain[2] = 32;
                buffer(plain).putInt(20, protocol == TransportProtocol.GWELL ? 3 : 3 << 16);
                assertThrows(IOException.class, () -> codec.encode(plain));
                if (protocol == TransportProtocol.IOTVIDEO) {
                    buffer(plain).putInt(20, (1 << 16) | (1 << 22));
                    assertThrows(IOException.class, () -> codec.encode(plain));
                }
            }
        }
    }
    @Test void backendsAreNotInterchangeable() throws Exception {
        try (var gwell = new GuteFrameCodec(TransportProtocol.GWELL); var iot = new GuteFrameCodec(TransportProtocol.IOTVIDEO)) {
            assertThrows(IOException.class, () -> iot.decode(gwell.encode(GwellRemoteMessages.list(1234))));
            assertThrows(IOException.class, () -> gwell.decode(iot.encode(IotVideoRemoteMessages.list(1234))));
        }
    }
}
