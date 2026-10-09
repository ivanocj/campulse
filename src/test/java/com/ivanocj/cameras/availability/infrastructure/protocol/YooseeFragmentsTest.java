package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.testsupport.YooseeFragmentFixtures;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

class YooseeFragmentsTest {
    @Test void reassemblesOutOfOrderAndDuplicateFragmentsWithSeparateNativeChunkSizes() throws Exception {
        for (var protocol : new TransportProtocol[]{TransportProtocol.GWELL, TransportProtocol.IOTVIDEO}) {
            for (boolean lan : new boolean[]{false, true}) {
                int chunk = protocol == TransportProtocol.GWELL ? 1128 : lan ? 1392 : 1008;
                var original = frame(0x7f, 6, chunk * 2 + 17, 1234, 9, 33);
                for (int i = 24; i < original.length; i++) { original[i] = (byte) i; }
                try (var receiver = new YooseeFragments(protocol)) {
                    var second = YooseeFragmentFixtures.fragment(original, 1234, 9, chunk, 1, lan);
                    var received = receiver.receive(second);
                    assertTrue(received.complete().isEmpty());
                    assertEquals(24, received.acknowledgement().length);
                    assertEquals(2, received.acknowledgement()[1]);
                    assertArrayEquals(Arrays.copyOfRange(second, 4, 24), Arrays.copyOfRange(received.acknowledgement(), 4, 24));
                    assertTrue(receiver.receive(second).complete().isEmpty());
                    assertTrue(receiver.receive(YooseeFragmentFixtures.fragment(original, 1234, 9, chunk, 2, lan)).complete().isEmpty());
                    assertArrayEquals(original, receiver.receive(YooseeFragmentFixtures.fragment(original, 1234, 9, chunk, 0, lan)).complete().orElseThrow());
                }
            }
        }
    }
    @Test void rejectsConflictingDuplicatesBadMaskCountsOversizeAndTruncation() throws Exception {
        var original = frame(0x7f, 6, 2000, 1, 2, 33);
        var fragment = YooseeFragmentFixtures.fragment(original, 1, 2, 1008, 0, false);
        try (var receiver = new YooseeFragments(TransportProtocol.IOTVIDEO)) {
            receiver.receive(fragment);
            var conflicting = fragment.clone(); conflicting[24] ^= 1;
            assertThrows(IOException.class, () -> receiver.receive(conflicting));
            var badMask = fragment.clone(); badMask[22] ^= 1;
            assertThrows(IOException.class, () -> receiver.receive(badMask));
            var truncated = Arrays.copyOf(fragment, fragment.length - 1);
            assertThrows(IOException.class, () -> receiver.receive(truncated));
            var wrongChunk = YooseeFragmentFixtures.fragment(original, 1, 2, 1128, 0, false);
            assertThrows(IOException.class, () -> receiver.receive(wrongChunk));
            var oversize = frame(0x7f, 6, 32792, 1, 2, 33);
            assertThrows(IOException.class, () -> receiver.receive(YooseeFragmentFixtures.fragment(oversize, 1, 2, 1008, 0, false)));
        }
    }
    @Test void boundsPendingAssembliesAndClearsThemOnClose() throws Exception {
        try (var receiver = new YooseeFragments(TransportProtocol.GWELL)) {
            var original = frame(0x7f, 6, 2000, 1, 2, 33);
            for (int i = 0; i < 4; i++) { receiver.receive(YooseeFragmentFixtures.fragment(original, 1, i, 1128, 0, false)); }
            assertThrows(IOException.class, () -> receiver.receive(YooseeFragmentFixtures.fragment(original, 1, 4, 1128, 0, false)));
            receiver.close();
            assertThrows(IllegalStateException.class, () -> receiver.receive(YooseeFragmentFixtures.fragment(original, 1, 4, 1128, 0, false)));
        }
    }
}
