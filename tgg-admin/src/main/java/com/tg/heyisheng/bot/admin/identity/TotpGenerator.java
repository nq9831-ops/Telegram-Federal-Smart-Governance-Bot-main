package com.tg.heyisheng.bot.admin.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/**
 * TOTP（RFC 6238）——后台账号的第二因子。
 *
 * <p><b>为什么需要</b>：超管「对系统全部掌控」，单靠密码一旦泄露即全盘失守。TOTP 让攻击者
 * 还需持有时序令牌（authenticator app）。
 *
 * <p><b>算法</b>：HMAC-SHA1、6 位、30 秒步长（RFC 6238 默认；与 Google Authenticator 等兼容），
 * 校验时接受 {@code ±1} 步的窗口以容忍时钟漂移。
 *
 * <p><b>密钥用 Base32 编码</b>（authenticator app 的通用格式），以便生成 {@code otpauth://} 供扫码。
 */
public final class TotpGenerator {

    private static final String HMAC_ALGO = "HmacSHA1";
    private static final int DIGITS = 6;
    private static final int STEP_SECONDS = 30;
    private static final int SECRET_BYTES = 20;   // 160 bit，RFC 4226 建议
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpGenerator() {
    }

    /** 生成一个新的 Base32 密钥。 */
    public static String newSecret() {
        byte[] buf = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(buf);
        return base32Encode(buf);
    }

    /** 生成 {@code otpauth://} URL（供 authenticator app 扫码）。 */
    public static String otpauthUrl(String issuer, String accountName, String base32Secret) {
        String label = urlEncode(issuer) + ":" + urlEncode(accountName);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(issuer)
                + "&digits=" + DIGITS
                + "&period=" + STEP_SECONDS;
    }

    /** 校验 6 位码（接受当前步 ±1）。 */
    public static boolean verify(String base32Secret, String code, Instant now) {
        if (base32Secret == null || base32Secret.isBlank() || code == null) {
            return false;
        }
        String normalized = code.trim();
        if (normalized.length() != DIGITS) {
            return false;
        }
        long counter = now.getEpochSecond() / STEP_SECONDS;
        for (long c = counter - 1; c <= counter + 1; c++) {
            if (MessageDigest.isEqual(
                    generateAt(base32Secret, c).getBytes(StandardCharsets.UTF_8),
                    normalized.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    /** 生成指定计数器下的 6 位码（供测试与校验）。 */
    static String generateAt(String base32Secret, long counter) {
        byte[] key = base32Decode(base32Secret);
        byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
        byte[] hash = hmac(key, data);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int otp = binary % 1_000_000;
        return String.format(Locale.ROOT, "%06d", otp);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(key, HMAC_ALGO));
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC 不可用", ex);
        }
    }

    /** Base32 编码（无填充）。 */
    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    /** Base32 解码（忽略填充与空白，大小写不敏感）。 */
    static byte[] base32Decode(String encoded) {
        String s = encoded.replace("=", "").replace(" ", "").trim().toUpperCase(Locale.ROOT);
        int buffer = 0;
        int bitsLeft = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            int val = BASE32.indexOf(s.charAt(i));
            if (val < 0) {
                throw new IllegalArgumentException("非法 Base32 字符：" + s.charAt(i));
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
