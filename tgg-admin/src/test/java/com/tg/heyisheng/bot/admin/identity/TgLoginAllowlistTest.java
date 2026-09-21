package com.tg.heyisheng.bot.admin.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TG 登录白名单（模块十一 · TG 用户登录授权闸门）。
 *
 * <p>守的重点：<b>空名单 = 无人可登</b>（fail-closed，不是 fail-open）——「没配授权名单」
 * 与「授权所有人」是两回事，后者会让后台对全网 Telegram 用户开放。
 */
class TgLoginAllowlistTest {

    @Test
    void blankAllowlistDeniesEveryone() {
        assertThat(new TgLoginAllowlist("").allows(42L)).as("空串 = 全拒").isFalse();
        assertThat(new TgLoginAllowlist(null).allows(42L)).as("null = 全拒").isFalse();
        assertThat(new TgLoginAllowlist("   ").allows(42L)).as("纯空白 = 全拒").isFalse();
        assertThat(new TgLoginAllowlist("").isEmpty()).isTrue();
    }

    @Test
    void listedUserIsAllowedAndOthersAreNot() {
        TgLoginAllowlist allowlist = new TgLoginAllowlist("42, 100, 7");

        assertThat(allowlist.allows(42L)).isTrue();
        assertThat(allowlist.allows(100L)).isTrue();
        assertThat(allowlist.allows(7L)).isTrue();
        assertThat(allowlist.allows(999L)).as("不在名单者不得放行").isFalse();
        assertThat(allowlist.size()).isEqualTo(3);
    }

    @Test
    void nonNumericTokenIsIgnoredNotFatal() {
        TgLoginAllowlist allowlist = new TgLoginAllowlist("42,abc,100");

        assertThat(allowlist.allows(42L)).as("一个笔误不得让整条配置失效").isTrue();
        assertThat(allowlist.allows(100L)).isTrue();
        assertThat(allowlist.size()).isEqualTo(2);
    }
}
