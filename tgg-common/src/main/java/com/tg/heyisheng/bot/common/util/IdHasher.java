package com.tg.heyisheng.bot.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 用户/群组标识的哈希化工具。
 *
 * <p><b>为什么必须哈希</b>：V5.0 明确要求「审核结果中的用户标识必须哈希化」。
 * 日志与审计记录里出现明文 userId 属于个人数据处理，是合规缺口——
 * 本项目的定位是隐私优先，这条不能含糊。
 *
 * <p><b>为什么必须加盐</b>：Telegram 的 userId 是递增数字、空间很小，
 * 无盐 SHA-256 可被直接枚举反推（遍历若干亿个 id 就能建表），
 * 那样"哈希化"只是视觉上的遮蔽，不构成实际保护。
 * 因此这里用 HMAC-SHA256，盐值由部署方配置。
 *
 * <p><b>盐的管理</b>：由装配层提供（Spring 配置项 {@code tgg.hash.salt}，可由环境变量
 * {@code TGG_HASH_SALT} 覆盖）。未配置时退回一个固定开发盐并<b>只警告不阻断</b>——因为本类用于
 * 日志脱敏，缺失盐不应让整个机器人起不来；但生产必须配置，否则哈希可被枚举。
 * {@link #fromEnvironment()} 是给非 Spring 调用方（单测、Builder 兜底）的便捷入口。
 */
public final class IdHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 开发期兜底盐。生产必须用 TGG_HASH_SALT 覆盖。 */
    static final String DEV_FALLBACK_SALT = "tgg-dev-only-salt";

    private static final int HASH_LENGTH = 12;

    private final byte[] salt;

    public IdHasher(String salt) {
        String effective = (salt == null || salt.isBlank()) ? DEV_FALLBACK_SALT : salt;
        this.salt = effective.getBytes(StandardCharsets.UTF_8);
    }

    /** 从环境变量构造；缺失时用开发兜底盐（调用方负责记录警告）。 */
    public static IdHasher fromEnvironment() {
        return new IdHasher(System.getenv("TGG_HASH_SALT"));
    }

    /**
     * 有效盐是否等于开发兜底盐（生产应检查此项并告警）。
     *
     * <p>判据是「<b>有效盐 == 兜底盐</b>」而非「有没有显式传参」：把盐显式设成兜底字面量，
     * 其可枚举性并不比缺省好，仍应告警。
     */
    public boolean usingDevFallbackSalt() {
        return java.util.Arrays.equals(salt, DEV_FALLBACK_SALT.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 哈希一个标识。
     *
     * @param id 原始标识，可为 null
     * @return 截断后的十六进制摘要；null 原样返回
     */
    public String hash(Long id) {
        if (id == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(salt, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(String.valueOf(id).getBytes(StandardCharsets.UTF_8));
            // 截断到 12 位十六进制（48 bit）：足以区分，且短到适合日志
            return HexFormat.of().formatHex(digest).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            // 理论上不会发生；发生了也不能把明文 id 落到日志里
            throw new IllegalStateException("标识哈希失败", ex);
        }
    }
}
