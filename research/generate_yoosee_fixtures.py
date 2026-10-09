"""Independent offline wire fixtures; synthetic identities only, no native SDK execution.

Uses Python's standard library and the system OpenSSL command for one AES vector.
Neither tool is an application or test dependency.
"""
import hashlib
import hmac
import struct
import subprocess


def rc5(key, block, rounds=6, word_bits=32):
    mask = (1 << word_bits) - 1
    width = word_bits // 8
    rotate = lambda x, n: ((x << (n % word_bits)) | (x >> ((-n) % word_bits))) & mask
    words = [int.from_bytes(key[i:i + width].ljust(width, b'\0'), 'little')
             for i in range(0, len(key), width)]
    p, q = ((0xb7e15163, 0x9e3779b9) if word_bits == 32
            else (0xb7e151628aed2a6b, 0x9e3779b97f4a7c15))
    schedule = [(p + i * q) & mask for i in range(2 * (rounds + 1))]
    a = b = i = j = 0
    for _ in range(3 * max(len(schedule), len(words))):
        a = schedule[i] = rotate((schedule[i] + a + b) & mask, 3)
        b = words[j] = rotate((words[j] + a + b) & mask, a + b)
        i, j = (i + 1) % len(schedule), (j + 1) % len(words)
    a = (int.from_bytes(block[:width], 'little') + schedule[0]) & mask
    b = (int.from_bytes(block[width:], 'little') + schedule[1]) & mask
    for r in range(1, rounds + 1):
        a = (rotate(a ^ b, b) + schedule[2 * r]) & mask
        b = (rotate(a ^ b, a) + schedule[2 * r + 1]) & mask
    return a.to_bytes(width, 'little') + b.to_bytes(width, 'little')


def list_frame(iot):
    size, command = (40, 0x15) if iot else (36, 0x2b)
    identity = 0x1111222233334444 if iot else 0x800004d2
    data = bytearray(struct.pack('<BBHQIII', 0x7f, command, size, identity, 0, 0, 0x10000 if iot else 1))
    data.extend(bytes(size - 24))
    if iot:
        data[24] = 1
    return encrypt_frame(data, iot).hex()


def gwell_init_frame():
    data = bytearray(struct.pack('<BBHQIII', 0x7f, 0x81, 80, 0x800004d2, 0x11223344, 0, 0x105))
    data.extend(bytes(56))
    data[29], data[30], data[38] = 6, 3, 1
    struct.pack_into('<II', data, 72, 21, 22)
    return encrypt_frame(data, False).hex()


def encrypt_frame(data, iot):
    data = bytearray(data)
    size = len(data)
    u32 = lambda offset: struct.unpack_from('<I', data, offset)[0]
    body_end = size - (80 if iot and u32(20) & (1 << 22) else 0) - (16 if iot and u32(20) & (1 << 24) else 0)
    checksum = u32(0) ^ u32(4) ^ u32(8) ^ u32(12) ^ (u32(20) & 0xffffff)
    if iot:
        for offset in range(24, body_end - 3, 4):
            checksum ^= u32(offset)
    else:
        checksum = sum(u32(i) for i in (0, 4, 8, 12, 20))
    struct.pack_into('<I', data, 16, checksum & 0xffffffff)
    key = data[:4] + data[20:23] + b'\0' if iot else data[:4] + data[20:24]
    data[12:20] = rc5(key, data[12:20])
    for offset in range(24, body_end - 7, 8):
        data[offset:offset + 8] = rc5(key, data[offset:offset + 8])
    # Native rc5_ctx_setkey explicitly takes 0x0c bytes; the C string's NUL is excluded.
    data[4:12] = rc5(b'www.gwell.cc', data[4:12])
    if iot:
        struct.pack_into('<II', data, 4, u32(4) ^ u32(12), u32(8) ^ u32(16))
    return data


def hash32(data):
    value = 0x4e67c6a7
    for octet in data:
        value = (value ^ (octet + (value << 5) + (value >> 2))) & 0xffffffff
    return value


def certify_frame():
    token = bytearray(range(64))
    token[0] = 1
    key, nonce = bytes(range(32)), bytes(range(12))
    sequence = 0x11223344
    data = bytearray(struct.pack('<BBHQIII', 0x7f, 0x0c, 164, 9999, sequence, 0, 0x14d0000))
    data.extend(bytes(140))
    struct.pack_into('<H', data, 24, 1)
    struct.pack_into('<I', data, 28, hash32(key))
    data[32:48] = rc5(token[48:64], key[:16], word_bits=64)
    data[48:64] = rc5(token[48:64], key[16:], word_bits=64)
    struct.pack_into('<I', data, 64, 1032)
    struct.pack_into('<Q', data, 68, 0x0102030405060708)
    # Recover the already-computed checksum by independently summing the pre-encryption words.
    words = struct.unpack('<6I', data[:24])
    checksum = words[0] ^ words[1] ^ words[2] ^ words[3] ^ (words[5] & 0xffffff)
    for offset in range(24, 68, 4):
        checksum ^= struct.unpack_from('<I', data, offset)[0]
    auth = bytearray(b'\1\1\0\0' + nonce + token[:48] + bytes(16))
    struct.pack_into('<H', auth, 2, sum(struct.unpack('<30H', auth[4:64])) & 0xffff)
    auth[64:80] = hmac.new(token[48:64], struct.pack('<II', sequence, checksum) + auth[:64], hashlib.md5).digest()
    a, b, c, d = 0, 0x4e67c6a7, 0x1505, 0
    for octet in nonce:
        a = (a * 131 + octet) & 0xffffffff
        b = (b ^ (octet + b * 32 + (b >> 2))) & 0xffffffff
        c = (c * 33 + octet) & 0xffffffff
        d = (d * 65599 + octet) & 0xffffffff
    aes_key = struct.pack('<4I', a, b, c, d)
    auth[16:64] = subprocess.run(
        ['openssl', 'enc', '-aes-128-cbc', '-K', aes_key.hex(), '-iv', b'iotVideo'.ljust(16, b'\0').hex(), '-nopad'],
        input=auth[16:64], stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout
    data[84:164] = auth
    return encrypt_frame(data, True).hex()


if __name__ == '__main__':
    assert rc5(bytes(16), bytes(8), rounds=12).hex() == '21a5dbee154b8f6d'
    print('GWELL_LIST', list_frame(False))
    print('IOTVIDEO_LIST', list_frame(True))
    print('GWELL_INIT', gwell_init_frame())
    for bits in (32, 64):
        print('RC5_6_' + str(bits), rc5(bytes(range(16)), bytes(range(bits // 4)), word_bits=bits).hex())
    print('IOTVIDEO_CERTIFY', certify_frame())
