package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.application.model.RemotePresence;
import com.ivanocj.cameras.availability.application.model.YooseeAccount;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import javax.crypto.Cipher;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

/** Current libp2pav status/registration layouts, not the old Android demo's presumed wire API. */
public final class GwellRemoteMessages {
    private GwellRemoteMessages() { }
    public static byte[] list(long userId) { return frame(0x7f, 0x2b, 36, userId, 0, 1); }

    public static byte[] initInfo(YooseeAccount.Gwell account, int sequence, int mode) {
        long userId = account.userId();
        var data = frame(0x7f, 0x81, mode == 1 ? 80 : 72, userId, sequence, mode | 4 | (mode == 1 ? 256 : 0));
        data[29] = 6;
        data[30] = (byte) ((userId & 0x80000000L) == 0 ? 7 : 3);
        data[38] = 1;
        if (mode == 1) { verification(data, account); }
        return data;
    }

    public static byte[] certify(YooseeAccount.Gwell account, int sequence) {
        var data = frame(0x7f, 3, 44, account.userId(), sequence, 1 | 12);
        buffer(data).putShort(22, (short) 1).putInt(24, account.code1()).putInt(28, account.code2())
                .putInt(32, account.sessionId1()).putInt(36, account.sessionId2()).putInt(40, hash(new byte[128]));
        return data;
    }

    public static byte[] keyMaterial(YooseeAccount.Gwell account, Instant now) {
        var data = new byte[32];
        buffer(data).putInt(4, (int) (now.getEpochSecond() / 7200)).putInt(8, (int) account.userId())
                .putInt(16, account.code1()).putInt(20, account.code2());
        return data;
    }

    public static byte[] updateKey(YooseeAccount.Gwell account, int sequence, byte[] certification, byte[] key, SecureRandom random)
            throws IOException {
        require(certification.length >= 32 && certification[1] == 4 && u16(certification, 22) == 0);
        int size = u16(certification, 28), bits = u16(certification, 30);
        require((size == 64 || size == 128) && bits == size * 8 && certification.length == 32 + size && key.length == 32);
        var modulus = new BigInteger(1, Arrays.copyOfRange(certification, 32, 32 + size));
        require(modulus.bitLength() == bits && modulus.testBit(0));
        byte[] encrypted;
        try {
            var rsa = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, BigInteger.valueOf(3)));
            var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, rsa, random);
            encrypted = cipher.doFinal(key);
        } catch (GeneralSecurityException invalid) {
            throw new IOException("GWell key negotiation failed.");
        }
        // gutes_add_send_pkt adds code1/code2 and sets opt_code for mode 1, except certification itself.
        var data = frame(0x7f, 5, 42 + encrypted.length, account.userId(), sequence, 1 | 12 | 256);
        buffer(data).putShort(22, (short) 1).putInt(24, i32(certification, 24))
                .putInt(28, hash(key)).putShort(32, (short) encrypted.length);
        System.arraycopy(encrypted, 0, data, 34, encrypted.length);
        Arrays.fill(encrypted, (byte) 0);
        verification(data, account);
        return data;
    }

    private static void verification(byte[] data, YooseeAccount.Gwell account) {
        buffer(data).putInt(data.length - 8, account.code1()).putInt(data.length - 4, account.code2());
    }

    public static byte[] status(long userId, int target, int sequence, int mode) {
        var data = frame(0x7f, 0x2d, 40, userId, sequence, mode | 12);
        buffer(data).putShort(22, (short) 1).putShort(30, (short) 1).putInt(32, target);
        return data;
    }

    public static RemotePresence.State presence(byte[] data, int target) throws IOException {
        require(data.length >= 32 && data[1] == 0x2e && u16(data, 22) == 0);
        int records = u16(data, 28), offline = u16(data, 30);
        long length = 32L + records * 52L + offline * 4L;
        require(records + offline <= 256 && length == data.length);
        Optional<RemotePresence.State> found = Optional.empty();
        for (int i = 0; i < records; i++) {
            int offset = 32 + i * 52;
            if (i32(data, offset) == target) {
                require(found.isEmpty());
                int state = data[offset + 8] & 255;
                found = Optional.of(state >= 2 && state < 32 ? RemotePresence.State.ONLINE : RemotePresence.State.OFFLINE);
            }
        }
        for (int i = 0; i < offline; i++) {
            if (i32(data, 32 + records * 52 + i * 4) == target) {
                require(found.isEmpty());
                found = Optional.of(RemotePresence.State.OFFLINE);
            }
        }
        return found.orElse(RemotePresence.State.UNKNOWN);
    }
}
