package com.tg.heyisheng.bot.listing.merchant;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复核人白名单单测。
 *
 * <p>门控的<b>默认方向必须是拒绝</b>：白名单为空、userId 为 null、条目为 null —
 * 这三种情况都必须落到 {@code false}。任何一处返回 true，就等于把「谁能审商家」
 * 交给了数据里的空值（fail-open）。
 */
class MerchantReviewGuardTest {

    @Test
    void emptyWhitelistRejectsEveryone() {
        MerchantReviewGuard guard = new MerchantReviewGuard(List.of());

        assertThat(guard.isReviewer(42L)).isFalse();
        assertThat(guard.size()).isZero();
    }

    @Test
    void nullUserIdIsRejected() {
        MerchantReviewGuard guard = new MerchantReviewGuard(List.of(42L));

        assertThat(guard.isReviewer(null)).isFalse();
    }

    @Test
    void listedUsersAreAcceptedOthersAreNot() {
        MerchantReviewGuard guard = new MerchantReviewGuard(List.of(42L, 43L));

        assertThat(guard.isReviewer(42L)).isTrue();
        assertThat(guard.isReviewer(43L)).isTrue();
        assertThat(guard.isReviewer(44L)).isFalse();
        assertThat(guard.size()).isEqualTo(2);
    }

    @Test
    void nullEntriesAreIgnoredNotTreatedAsWildcard() {
        MerchantReviewGuard guard = new MerchantReviewGuard(Arrays.asList(42L, null));

        assertThat(guard.size()).as("空条目应被丢弃，而不是变成「谁都能过」").isEqualTo(1);
        assertThat(guard.isReviewer(42L)).isTrue();
    }

    @Test
    void nullWhitelistIsTreatedAsEmpty() {
        MerchantReviewGuard guard = new MerchantReviewGuard((Iterable<Long>) null);

        assertThat(guard.size()).isZero();
        assertThat(guard.isReviewer(42L)).isFalse();
    }

    /** 热生效：经配置服务读取时，改名单无需重启。 */
    @Test
    void configBackedGuardReflectsChangesWithoutRestart() {
        com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService cfg =
                new com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService(
                        org.mockito.Mockito.mock(com.tg.heyisheng.bot.core.config.dynamic.ConfigOverrideRepository.class),
                        new org.springframework.mock.env.MockEnvironment());
        MerchantReviewGuard guard = new MerchantReviewGuard(cfg);
        assertThat(guard.isReviewer(42L)).isFalse();

        cfg.set(MerchantReviewGuard.KEY, "42", null);

        assertThat(guard.isReviewer(42L)).as("改商家复核人名单后无需重启即生效").isTrue();
    }
}
