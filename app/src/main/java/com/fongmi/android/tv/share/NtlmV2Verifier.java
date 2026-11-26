package com.fongmi.android.tv.share;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SMB 分享的口令校验：只按 MS-NLMP 的 NTLMv2 走一遍服务端计算，和客户端算出的 NTProofStr 比对。
 *
 * <p>不实现 NTLMv1（需要 DES 且强度太低）：本项目的客户端在 Type2 里拿到扩展会话安全标志后一律
 * 走 NTLMv2，Windows 同样如此；真收到 24 字节的 v1 响应就直接判失败。</p>
 */
final class NtlmV2Verifier {

    private NtlmV2Verifier() {
    }

    /** @param type3 从 NTLMSSP 签名开始的 Type3 消息（已去掉 SPNEGO 外壳）。 */
    static boolean verify(byte[] type3, byte[] serverChallenge, String username, String password) {
        if (type3 == null || serverChallenge == null || serverChallenge.length != 8) return false;
        if (username == null || password == null || username.isEmpty()) return false;
        try {
            int signature = indexOfSignature(type3);
            if (signature < 0) return false;
            byte[] message = new byte[type3.length - signature];
            System.arraycopy(type3, signature, message, 0, message.length);
            if (message.length < 64 || readInt(message, 8) != 3) return false;

            int ntLength = readShort(message, 20);
            int ntOffset = readInt(message, 24);
            int domainLength = readShort(message, 28);
            int domainOffset = readInt(message, 32);
            int userLength = readShort(message, 36);
            int userOffset = readInt(message, 40);
            if (ntLength < 24 || ntOffset < 0 || ntOffset + ntLength > message.length) return false;

            byte[] ntResponse = slice(message, ntOffset, ntLength);
            byte[] ntProof = slice(ntResponse, 0, 16);
            byte[] blob = slice(ntResponse, 16, ntResponse.length - 16);

            String user = readString(message, userOffset, userLength);
            String domain = readString(message, domainOffset, domainLength);

            // NTLM 用户名在协议层就是大写不敏感，服务端按不区分大小写比对。
            if (!user.equalsIgnoreCase(username)) return false;

            byte[] responseKey = hmacMd5(md4(unicode(password)),
                    concat(unicode(user.toUpperCase(Locale.ROOT)), unicode(domain)));
            byte[] expected = hmacMd5(responseKey, concat(serverChallenge, blob));
            return MessageDigest.isEqual(expected, ntProof);
        } catch (Exception error) {
            return false;
        }
    }

    private static int indexOfSignature(byte[] data) {
        for (int i = 0; i + 8 <= data.length; i++) {
            if (data[i] == 'N' && data[i + 1] == 'T' && data[i + 2] == 'L' && data[i + 3] == 'M'
                    && data[i + 4] == 'S' && data[i + 5] == 'S' && data[i + 6] == 'P' && data[i + 7] == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String readString(byte[] data, int offset, int length) {
        if (length <= 0) return "";
        return new String(slice(data, offset, length), StandardCharsets.UTF_16LE);
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        if (offset < 0 || length <= 0 || offset + length > data.length) return new byte[0];
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }

    private static int readShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static byte[] unicode(String value) {
        return value.getBytes(StandardCharsets.UTF_16LE);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static byte[] hmacMd5(byte[] key, byte[] message) throws Exception {
        Mac mac = Mac.getInstance("HmacMD5");
        mac.init(new SecretKeySpec(key, "HmacMD5"));
        return mac.doFinal(message);
    }

    /** MD4（NT 口令散列）；Java 自带的摘要算法里没有，这里按 RFC 1320 实现。 */
    static byte[] md4(byte[] input) {
        int a = 0x67452301, b = 0xefcdab89, c = 0x98badcfe, d = 0x10325476;
        int length = input.length;
        int paddedLength = ((length + 8) / 64 + 1) * 64;
        byte[] padded = new byte[paddedLength];
        System.arraycopy(input, 0, padded, 0, length);
        padded[length] = (byte) 0x80;
        long bits = (long) length * 8;
        for (int i = 0; i < 8; i++) padded[paddedLength - 8 + i] = (byte) (bits >>> (8 * i));

        int[] order3 = { 0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15 };
        for (int chunk = 0; chunk < paddedLength; chunk += 64) {
            int[] x = new int[16];
            for (int i = 0; i < 16; i++) {
                int at = chunk + i * 4;
                x[i] = (padded[at] & 0xFF) | ((padded[at + 1] & 0xFF) << 8)
                        | ((padded[at + 2] & 0xFF) << 16) | ((padded[at + 3] & 0xFF) << 24);
            }
            int aa = a, bb = b, cc = c, dd = d;
            // Round 1：[A B C D k s] 依次作用在 A、D、C、B 上。
            for (int group = 0; group < 4; group++) {
                int base = group * 4;
                aa = ff(aa, bb, cc, dd, x[base], 3);
                dd = ff(dd, aa, bb, cc, x[base + 1], 7);
                cc = ff(cc, dd, aa, bb, x[base + 2], 11);
                bb = ff(bb, cc, dd, aa, x[base + 3], 19);
            }
            // Round 2：k 顺序 0,4,8,12 / 1,5,9,13 / 2,6,10,14 / 3,7,11,15
            for (int group = 0; group < 4; group++) {
                aa = gg(aa, bb, cc, dd, x[group], 3);
                dd = gg(dd, aa, bb, cc, x[group + 4], 5);
                cc = gg(cc, dd, aa, bb, x[group + 8], 9);
                bb = gg(bb, cc, dd, aa, x[group + 12], 13);
            }
            // Round 3：k 顺序 0,8,4,12 / 2,10,6,14 / 1,9,5,13 / 3,11,7,15
            for (int group = 0; group < 4; group++) {
                int base = group * 4;
                aa = hh(aa, bb, cc, dd, x[order3[base]], 3);
                dd = hh(dd, aa, bb, cc, x[order3[base + 1]], 9);
                cc = hh(cc, dd, aa, bb, x[order3[base + 2]], 11);
                bb = hh(bb, cc, dd, aa, x[order3[base + 3]], 15);
            }
            a += aa;
            b += bb;
            c += cc;
            d += dd;
        }
        byte[] out = new byte[16];
        writeInt(out, 0, a);
        writeInt(out, 4, b);
        writeInt(out, 8, c);
        writeInt(out, 12, d);
        return out;
    }

    private static int ff(int a, int b, int c, int d, int x, int s) {
        return rotate(a + ((b & c) | (~b & d)) + x, s);
    }

    private static int gg(int a, int b, int c, int d, int x, int s) {
        return rotate(a + ((b & c) | (b & d) | (c & d)) + x + 0x5a827999, s);
    }

    private static int hh(int a, int b, int c, int d, int x, int s) {
        return rotate(a + (b ^ c ^ d) + x + 0x6ed9eba1, s);
    }

    private static int rotate(int value, int bits) {
        return (value << bits) | (value >>> (32 - bits));
    }

    private static void writeInt(byte[] out, int offset, int value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >> 8);
        out[offset + 2] = (byte) (value >> 16);
        out[offset + 3] = (byte) (value >> 24);
    }
}
