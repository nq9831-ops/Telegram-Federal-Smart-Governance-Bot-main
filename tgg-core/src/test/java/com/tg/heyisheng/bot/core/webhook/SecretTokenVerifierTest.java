package com.tg.heyisheng.bot.core.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecretTokenVerifier 测试。
 *
 * <p><b>注意</b>：本测试只验证比较<b>语义</b>正确（该拒绝的都拒绝）。
 * 「常量时间」是时序属性，单元测试无法证明——那一点只由代码审查确认使用了
 * {@code MessageDigest.isEqual}，本测试不声称测过时序。
 */
class SecretTokenVerifierTest {

    private static final String SECRET = "s3cr3t-token-value";

    private final SecretTokenVerifier verifier = new SecretTokenVerifier(SECRET);

    @Test
    void acceptsExactMatch() {
        assertThat(verifier.verify(SECRET)).isTrue();
    }

    @Test
    void rejectsWrongValue() {
        assertThat(verifier.verify("wrong-value")).isFalse();
    }

    @Test
    void rejectsNullAndEmpty() {
        assertThat(verifier.verify(null)).isFalse();
        assertThat(verifier.verify("")).isFalse();
    }

    @Test
    void rejectsDifferentLength() {
        assertThat(verifier.verify(SECRET + "x")).isFalse();
        assertThat(verifier.verify(SECRET.substring(0, SECRET.length() - 1))).isFalse();
    }

    @Test
    void rejectsCaseVariant() {
        assertThat(verifier.verify(SECRET.toUpperCase())).isFalse();
    }
}
