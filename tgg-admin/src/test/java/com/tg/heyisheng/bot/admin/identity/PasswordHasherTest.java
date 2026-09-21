package com.tg.heyisheng.bot.admin.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码哈希（PBKDF2）。
 *
 * <p>守两件事：① 正确密码能过、错误密码不能过；② <b>格式非法/空输入一律返回 false 而不抛异常</b>
 * ——否则一个坏掉的哈希串会把登录打成 500，而不是干净的「密码错误」。
 */
class PasswordHasherTest {

    @Test
    void hashThenMatches() {
        String h = PasswordHasher.hash("s3cret-密码");

        assertThat(h).startsWith("pbkdf2$");
        assertThat(PasswordHasher.matches("s3cret-密码", h)).isTrue();
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        String h = PasswordHasher.hash("right");

        assertThat(PasswordHasher.matches("wrong", h)).isFalse();
    }

    @Test
    void samePasswordHashesDifferently() {
        // 盐随机：同一密码两次哈希必须不同，否则离线爆破可用彩虹表
        assertThat(PasswordHasher.hash("x")).isNotEqualTo(PasswordHasher.hash("x"));
    }

    @Test
    void malformedOrNullNeverThrows() {
        assertThat(PasswordHasher.matches("x", "not-a-hash")).isFalse();
        assertThat(PasswordHasher.matches("x", "pbkdf2$abc$def$ghi")).isFalse();
        assertThat(PasswordHasher.matches(null, PasswordHasher.hash("x"))).isFalse();
        assertThat(PasswordHasher.matches("x", null)).isFalse();
    }
}
