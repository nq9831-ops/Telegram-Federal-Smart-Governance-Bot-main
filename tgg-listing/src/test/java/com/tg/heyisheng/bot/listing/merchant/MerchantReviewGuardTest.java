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
        MerchantReviewGuard guard = new MerchantReviewGuard(null);

        assertThat(guard.size()).isZero();
        assertThat(guard.isReviewer(42L)).isFalse();
    }
}
