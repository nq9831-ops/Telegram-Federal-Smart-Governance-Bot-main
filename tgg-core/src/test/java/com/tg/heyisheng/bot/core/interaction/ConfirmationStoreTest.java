package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.Confirm;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确认登记簿测试。
 *
 * <p>守四条安全性质：① 三态判定正确（含「带参数才确认」）；② 令牌**一次性**；
 * ③ 非发起者消费不了、且不会把令牌消耗掉；④ 过期即失效。
 */
class ConfirmationStoreTest {

    private static final long CHAT = -100L;
    private static final long USER = 42L;
    private static final long OTHER = 999L;
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");

    private static final class MutableClock extends Clock {
        private Instant now = NOW;

        void advance(Duration delta) {
            now = now.plus(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static UpdateContext ctx(String args) {
        return new UpdateContext(1, USER, CHAT, 9, "disable", args);
    }

    @Test
    void neverModeNeedsNoConfirmation() {
        ConfirmationStore store = new ConfirmationStore(new MutableClock());

        assertThat(store.requiresConfirmation(ctx(null), Confirm.NEVER)).isFalse();
    }

    @Test
    void alwaysModeNeedsConfirmation() {
        ConfirmationStore store = new ConfirmationStore(new MutableClock());

        assertThat(store.requiresConfirmation(ctx(null), Confirm.ALWAYS)).isTrue();
    }

    /** {@code /data_breach} 的语义：无参只读列出（不该打断），带参写证据（该确认）。 */
    @Test
    void whenArgsDependsOnPresenceOfArguments() {
        ConfirmationStore store = new ConfirmationStore(new MutableClock());

        assertThat(store.requiresConfirmation(ctx(null), Confirm.WHEN_ARGS)).isFalse();
        assertThat(store.requiresConfirmation(ctx("   "), Confirm.WHEN_ARGS))
                .as("空白等同于没带参数").isFalse();
        assertThat(store.requiresConfirmation(ctx("泄露 3"), Confirm.WHEN_ARGS)).isTrue();
    }

    /** 已确认的本次执行不再拦——标记只可能由桥在令牌消费后挂上。 */
    @Test
    void alreadyGrantedExecutionSkipsConfirmation() {
        ConfirmationStore store = new ConfirmationStore(new MutableClock());
        UpdateContext confirmed = ctx(null);
        confirmed.attach(new ConfirmationGranted());

        assertThat(store.requiresConfirmation(confirmed, Confirm.ALWAYS)).isFalse();
    }

    @Test
    void issuedNonceIsSingleUse() {
        ConfirmationStore store = new ConfirmationStore(new MutableClock());
        String nonce = store.issue(ctx("广告"));

        assertThat(store.consume(nonce, USER)).isPresent();
        assertThat(store.consume(nonce, USER)).as("一次性：消费即删，重放无效").isEmpty();
    }

    /** 非发起者：既拿不到，也不能把别人的令牌消耗掉（否则等于能用乱点破坏他人操作）。 */
    @Test
    void anotherUserCannotConsumeNorDestroyTheToken() {
        ConfirmationStore store = new ConfirmationStore(new MutableClock());
        String nonce = store.issue(ctx("广告"));

        assertThat(store.consume(nonce, OTHER)).as("非发起者消费不到").isEmpty();
        assertThat(store.consume(nonce, USER))
                .as("被他人点过之后，发起者仍能用（令牌未被消耗）")
                .isPresent();
    }

    @Test
    void expiredNonceIsRejected() {
        MutableClock clock = new MutableClock();
        ConfirmationStore store = new ConfirmationStore(clock);
        String nonce = store.issue(ctx("广告"));

        clock.advance(ConfirmationStore.TTL.plusSeconds(1));

        assertThat(store.consume(nonce, USER)).isEmpty();
        assertThat(store.size()).as("过期项应被清理").isZero();
    }

    @Test
    void unknownNonceIsRejected() {
        ConfirmationStore store = new ConfirmationStore(new MutableClock());

        assertThat(store.consume("nope", USER)).isEmpty();
        assertThat(store.consume(null, USER)).isEmpty();
    }

    /** 超过上限时淘汰最旧的一条，而不是抛错让用户卡住。 */
    @Test
    void exceedingCapacityEvictsOldestInsteadOfFailing() {
        MutableClock clock = new MutableClock();
        ConfirmationStore store = new ConfirmationStore(clock);
        String first = null;
        for (int i = 0; i < ConfirmationStore.MAX_PENDING; i++) {
            String nonce = store.issue(ctx("第" + i));
            if (first == null) {
                first = nonce;
            }
            clock.advance(Duration.ofMillis(1)); // 过期时刻递增（但都远未到期），便于判定「最旧」
        }

        String newest = store.issue(ctx("第 N"));

        assertThat(store.size()).as("不超过上限").isEqualTo(ConfirmationStore.MAX_PENDING);
        assertThat(store.consume(first, USER)).as("最旧的一条被淘汰").isEmpty();
        assertThat(store.consume(newest, USER)).as("最新的一条仍可用").isPresent();
    }

    /** 消费后取出的是发起时那件事：群、命令、参数都要原样带回。 */
    @Test
    void consumedActionCarriesOriginalCommandAndArgs() {
        ConfirmationStore store = new ConfirmationStore(new MutableClock());
        String nonce = store.issue(new UpdateContext(1, USER, CHAT, 9, "delword", "广告"));

        Optional<ConfirmationStore.PendingAction> action = store.consume(nonce, USER);

        assertThat(action).isPresent();
        assertThat(action.get().chatId()).isEqualTo(CHAT);
        assertThat(action.get().userId()).isEqualTo(USER);
        assertThat(action.get().command()).isEqualTo("delword");
        assertThat(action.get().args()).isEqualTo("广告");
    }
}
