package com.tg.heyisheng.bot.core.moderation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 消息内容的**指纹**：把正文归一化后取 SHA-256，只保留哈希。
 *
 * <p><b>存在理由</b>：反刷屏需要判断"同一用户是否在重复发相同内容"，那就必须比较内容——
 * 而本项目硬约束是「消息原文零存储」。指纹是调和这对矛盾的唯一办法：
 * 内存与日志里只出现哈希，原文用完即丢（由 {@code MessageScrubber} 负责清除）。
 *
 * <p><b>为什么不需要加盐</b>：与 {@code IdHasher} 不同——那个哈希会进日志，属对外可见的审计字段，
 * 无盐可被枚举反推；本指纹**只在进程内存里存活**（作为滑动窗口的 key），不落库、不进日志
 * （日志只记规则 id），因此无需加盐。
 *
 * <p><b>归一化只做 trim</b>：刻意不做大小写折叠——"SPAM" 与 "spam" 视为不同内容更安全
 * （宁可漏判重复，也不要误判两个不同内容为同一条而删错消息）。
 */
public final class ContentFingerprint {

    private static final int HASH_LENGTH = 16;

    private ContentFingerprint() {
    }

    /**
     * 计算内容指纹。
     *
     * @param content 消息正文；null 或空白返回 null（无可比较的内容）
     * @return 十六进制摘要；无内容时为 null
     */
    public static String of(String content) {
        if (content == null) {
            return null;
        }
        String normalized = content.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException ex) {
            // 理论上不会发生（JVM 必带 SHA-256）；发生了也不能回退成明文
            throw new IllegalStateException("内容指纹计算失败", ex);
        }
    }
}
