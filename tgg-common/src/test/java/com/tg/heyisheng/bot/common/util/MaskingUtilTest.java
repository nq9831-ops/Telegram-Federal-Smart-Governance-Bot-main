package com.tg.heyisheng.bot.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MaskingUtil 测试。
 *
 * <p>约束来源：V5.0「日志中禁止出现 Token 和消息原文」。
 */
class MaskingUtilTest {

    @Test
    void maskToken_keepsHeadAndTail_masksMiddle() {
        assertThat(MaskingUtil.maskToken("1234567890abcdef"))
                .isEqualTo("1234***cdef");
    }

    @Test
    void maskToken_shortValue_isFullyMasked() {
        assertThat(MaskingUtil.maskToken("short")).isEqualTo("***");
    }

    @Test
    void maskToken_null_returnsNull() {
        assertThat(MaskingUtil.maskToken(null)).isNull();
    }

    @Test
    void maskToken_empty_returnsEmpty() {
        assertThat(MaskingUtil.maskToken("")).isEmpty();
    }

    @Test
    void maskText_shortText_unchanged() {
        assertThat(MaskingUtil.maskText("你好")).isEqualTo("你好");
    }

    @Test
    void maskText_longText_truncatedWithLengthHint() {
        String longText = "a".repeat(100);

        String masked = MaskingUtil.maskText(longText);

        assertThat(masked).startsWith("aaaaaaaaaaaaaaaa");
        assertThat(masked).contains("100");
        assertThat(masked.length()).isLessThan(longText.length());
    }

    @Test
    void maskText_null_returnsNull() {
        assertThat(MaskingUtil.maskText(null)).isNull();
    }
}
