package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code CreditService#ensureInitialized} 单测——模块六商家信用分的那个「显式初值入口」。
 *
 * <p>本波次要证明的唯一一件事：<b>初值由调用方决定，且不动既有的共用默认值</b>。
 * 分值真的落库由 {@code MerchantOnboardingIT} 直查 {@code credit_scores} 断言。
 */
class CreditServiceEnsureInitializedTest {

    private final CreditRuleEngine ruleEngine = mock(CreditRuleEngine.class);
    private final CreditScoreRepository repository = mock(CreditScoreRepository.class);
    private final CreditService service =
            new CreditService(ruleEngine, repository, new IdHasher("test-salt"));

    @Test
    void writesConfiguredScoreForNewSubject() {
        when(repository.insertIfAbsent(eq("MERCHANT"), eq(77L), eq(500), any(Instant.class)))
                .thenReturn(1);

        assertThat(service.ensureInitialized(CreditSubjectType.MERCHANT, 77L, 500)).isTrue();

        verify(repository).insertIfAbsent(eq("MERCHANT"), eq(77L), eq(500), any(Instant.class));
    }

    @Test
    void reportsFalseWhenRowAlreadyExists() {
        when(repository.insertIfAbsent(anyString(), anyLong(), anyInt(), any(Instant.class)))
                .thenReturn(0);

        assertThat(service.ensureInitialized(CreditSubjectType.MERCHANT, 77L, 500))
                .as("已有账本行时 INSERT IGNORE 静默跳过：不得把已有分值打回初值")
                .isFalse();
    }

    @Test
    void nullSubjectTypeIsRejectedWithoutTouchingLedger() {
        assertThat(service.ensureInitialized(null, 77L, 500)).isFalse();

        verify(repository, never()).insertIfAbsent(anyString(), anyLong(), anyInt(), any(Instant.class));
    }

    @Test
    void doesNotChangeTheSharedInitialScoreConstant() {
        assertThat(CreditService.INITIAL_SCORE)
                .as("模块六的 500 不得回头改动三套分共用的初值契约（起始 100）")
                .isEqualTo(100);
    }
}
