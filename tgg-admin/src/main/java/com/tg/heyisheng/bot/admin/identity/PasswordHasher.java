package com.tg.heyisheng.bot.admin.identity;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * 后台账号密码哈希（PBKDF2-HMAC-SHA256，JDK 内置，不引第三方）。
 *
 * <p><b>为什么是 PBKDF2 而不是明文/裸 SHA-256</b>：库被读走时，明文密码会被直接复用；
 * 而裸 SHA-256 对高价值目标可被 GPU 暴力破解。PBKDF2 带随机盐 + 高迭代次数，
 * 使离线爆破成本显著上升，且同一密码两次哈希结果不同（盐随机）。
 *
 * <p><b>为什么不用 BCrypt</b>：本模块刻意不引新依赖（见 {@code tgg-admin} 的 pom 注释）；
 * PBKDF2 是 JDK 自带，无需第三方。
 *
 * <p><b>存储格式</b>：{@code pbkdf2$<iterations>$<saltBase64>$<hashBase64>}。
 * 迭代次数随哈希一起存，便于将来提升而不破坏旧哈希（旧哈希用其自带迭代数校验）。
 *
 * <p><b>常量时间比对</b>：用 {@link MessageDigest#isEqual}，与 webhook secret 校验同一实现，
 * 避免按字节短路比较泄露前缀。
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";
    /** 迭代次数（OWASP 2023 对 PBKDF2-HMAC-SHA256 的建议下限约 210k）。 */
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /** 生成密码哈希（每次调用盐不同）。 */
    public static String hash(String password) {
        Objects.requireNonNull(password, "password 不能为 null");
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS, KEY_BITS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
    }

    /**
     * 校验密码是否匹配已存储的哈希。
     *
     * @param password 待校验的明文密码，可为 null（返回 false）
     * @param encoded  存储的哈希串，格式非法时返回 false（不抛异常，避免把格式问题变成 500）
     * @return 是否匹配
     */
    public static boolean matches(String password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (RuntimeException ex) {
            return false;
        }
        if (iterations <= 0 || salt.length == 0 || expected.length == 0) {
            return false;
        }
        // keyBits 取自 expected 长度：即便存储串被篡改，也按实际长度派生再比对（长度不符即不匹配）
        byte[] actual = derive(password, salt, iterations, expected.length * 8);
        return MessageDigest.isEqual(actual, expected);
    }

    private static byte[] derive(String password, byte[] salt, int iterations, int keyBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyBits);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("密码哈希计算失败", ex);
        }
    }
}
