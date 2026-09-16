package com.tg.heyisheng.bot.common.util;

/**
 * 日志脱敏工具。
 *
 * <p>硬约束（V5.0 文档 + 项目 AGENTS.md）：日志中禁止出现 Bot Token 与消息原文。
 * 本类是全项目日志出口的集中脱敏入口，任何要打印敏感值的日志都应先经此处。
 */
public final class MaskingUtil {

    /** 遮蔽后中间固定显示的内容。 */
    private static final String MASK = "***";

    /** Token 类字符串保留的首尾长度。 */
    private static final int TOKEN_KEEP = 4;

    /** 正文预览保留的字符数。 */
    private static final int TEXT_PREVIEW = 16;

    /** 正文超过该长度才截断。 */
    private static final int TEXT_MAX = 32;

    private MaskingUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 遮蔽密钥类字符串（Bot Token、Webhook Secret 等）：保留首尾各 4 位。
     *
     * @param token 原始值，可为 null
     * @return null 与空串原样返回；长度不足以保留首尾时整体遮蔽为 {@code ***}
     */
    public static String maskToken(String token) {
        if (token == null || token.isEmpty()) {
            return token;
        }
        if (token.length() <= TOKEN_KEEP * 2) {
            return MASK;
        }
        return token.substring(0, TOKEN_KEEP) + MASK + token.substring(token.length() - TOKEN_KEEP);
    }

    /**
     * 遮蔽消息正文：超长时截断为预览 + 长度提示。
     *
     * <p><b>注意</b>：这是「降低泄露面」，不等同于「零存储」。真正的零存储由切片 3 的
     * 处理管道保证（审核完成后丢弃正文、不落盘）。
     */
    public static String maskText(String text) {
        if (text == null) {
            return null;
        }
        if (text.length() <= TEXT_MAX) {
            return text;
        }
        return text.substring(0, TEXT_PREVIEW) + "...(" + text.length() + " chars)";
    }
}
