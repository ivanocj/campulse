package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.application.model.YooseeAccount;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

/** Current IoTVideo type-1 account registration, identity mapping, and correlated full-model queries. */
public final class IotVideoRemoteMessages {
    public static final int MTU = 1032; // Current iv_unit_init default; ordinary fragment bodies are MTU - 24.
    private IotVideoRemoteMessages() { }
    public static byte[] list(long identity) {
        var data = frame(0x7f, 0x15, 40, identity, 0, 1 << 16);
        buffer(data).putShort(24, (short) 1);
        return data;
    }

    public static byte[] certify(YooseeAccount.IotVideo account, byte[] key, byte[] nonce, int sequence, long tick,
                                 GuteFrameCodec codec) throws IOException {
        require(key.length == 32 && nonce.length == 12);
        // 64-byte base + advertised 4-byte MTU + 16-byte SNTP trailer + 80-byte signed token appendix.
        var data = frame(0x7f, 0x0c, 164, account.wireId(), sequence, (1 << 16) | (3 << 18) | (1 << 22) | (1 << 24));
        var token = account.token();
        var tokenKey = Arrays.copyOfRange(token, 48, 64);
        var auth = new byte[80];
        var signed = new byte[72];
        try {
            buffer(data).putShort(24, (short) 1).putInt(28, hash(key)).putInt(64, MTU);
            System.arraycopy(key, 0, data, 32, 32);
            try (var rc5 = new YooseeRc5(16, 6, tokenKey)) {
                rc5.encrypt(data, 32);
                rc5.encrypt(data, 48);
            }
            buffer(data).putLong(68, tick);
            auth[0] = 1;
            auth[1] = 1;
            System.arraycopy(nonce, 0, auth, 4, 12);
            System.arraycopy(token, 0, auth, 16, 48);
            int sum = 0;
            for (int i = 4; i < 64; i += 2) { sum += u16(auth, i); }
            buffer(auth).putShort(2, (short) sum);
            // Native checksum excludes both trailers but includes the token-encrypted key challenge.
            buffer(signed).putInt(sequence).putInt(codec.checksum(data));
            System.arraycopy(auth, 0, signed, 8, 64);
            var mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(tokenKey, "HmacMD5"));
            System.arraycopy(mac.doFinal(signed), 0, auth, 64, 16);
            var aesKey = expandNonce(nonce);
            try {
                var aes = Cipher.getInstance("AES/CBC/NoPadding");
                aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                        new IvParameterSpec(Arrays.copyOf("iotVideo".getBytes(StandardCharsets.US_ASCII), 16)));
                var encrypted = aes.doFinal(auth, 16, 48);
                System.arraycopy(encrypted, 0, auth, 16, 48);
                Arrays.fill(encrypted, (byte) 0);
            } finally { Arrays.fill(aesKey, (byte) 0); }
            System.arraycopy(auth, 0, data, 84, 80);
            return codec.encode(data);
        } catch (GeneralSecurityException failed) {
            throw new IOException("IoTVideo certification cryptography failed.");
        } finally {
            Arrays.fill(token, (byte) 0);
            Arrays.fill(tokenKey, (byte) 0);
            Arrays.fill(data, (byte) 0);
            Arrays.fill(auth, (byte) 0);
            Arrays.fill(signed, (byte) 0);
        }
    }

    static byte[] expandNonce(byte[] nonce) {
        int a = 0, b = 0x4e67c6a7, c = 0x1505, d = 0;
        for (byte octet : nonce) {
            int value = octet & 255;
            a = a * 131 + value;
            b ^= value + (b << 5) + (b >>> 2);
            c = c * 33 + value;
            d = d * 65599 + value;
        }
        return buffer(new byte[16]).putInt(a).putInt(b).putInt(c).putInt(d).array();
    }

    public static byte[] initInfo(long sessionId, int sequence) {
        var data = frame(0x7e, 0xa6, 62, sessionId, sequence, (2 << 16) | (3 << 18));
        buffer(data).putShort(24, (short) 62).putInt(32, 0x28000000);
        data[27] = 2;
        return data;
    }

    public static Map<String, Long> identities(byte[] data) throws IOException {
        require(data.length >= 28 && (data[1] & 255) == 0xa7 && u16(data, 26) == 0);
        if ((u16(data, 24) & 1) == 0) { return Map.of(); }
        require(data.length >= 32);
        int count = u16(data, 28) + u16(data, 30);
        require(count <= 1024 && 32L + count * 28L <= data.length);
        var result = new LinkedHashMap<String, Long>();
        for (int i = 0; i < count; i++) {
            int offset = 32 + 28 * i;
            long did = i64(data, offset);
            require(did != 0);
            byte[] tid = Arrays.copyOfRange(data, offset + 12, offset + 28);
            boolean numeric = true;
            for (byte octet : tid) { numeric &= octet == 0; }
            String id = numeric ? Long.toUnsignedString(did) : HexFormat.of().formatHex(tid);
            require(result.putIfAbsent(id, did) == null);
        }
        return Map.copyOf(result);
    }

    public static long resolveIdentity(String tid, Map<String, Long> mappings) throws IOException {
        if (tid.length() < 32 && tid.matches("[0-9]+")) {
            try {
                long id = Long.parseUnsignedLong(tid);
                if (id != 0) { return id; }
            } catch (NumberFormatException invalid) { /* No partial atoll conversion. */ }
        }
        var id = mappings.get(tid); // Preserve case: native map lookup is case-sensitive.
        if (id == null) { throw new IOException("IoTVideo device identifier was not mapped by this account registration."); }
        return id;
    }

    public static byte[] model(long sessionId, long target, int sequence) {
        var data = frame(0x7e, 0xb7, 39, sessionId, sequence, (2 << 16) | (3 << 18));
        buffer(data).putLong(24, target); // Empty path, no cache-dependent partial-model shortcut.
        return data;
    }

    public static byte[] modelJson(byte[] data, long target) throws IOException {
        require(data.length >= 40 && (data[1] & 255) == 0xb8 && i64(data, 24) == target);
        require(u16(data, 36) == 0 && (data[32] & 1) != 0);
        int length = u16(data, 38);
        require(length > 0 && data.length == 41 + length && data[data.length - 1] == 0);
        return Arrays.copyOfRange(data, 40, 40 + length);
    }
}
