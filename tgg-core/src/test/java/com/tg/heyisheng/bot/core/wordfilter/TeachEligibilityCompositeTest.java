package com.tg.heyisheng.bot.core.wordfilter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TeachEligibility#composite} 的聚合行为（模块九 §10.3）。
 *
 * <p>这是「多条门槛来自不同模块」这件事的正确性所在：<b>任一门槛拒绝即拒绝</b>，
 * 且所有未通过的原因要一次说清（门槛是并列条件，不是二选一）。
 *
 * <p>为什么值得单测：聚合器是 core 与 tgg-credit 唯一的交点——它错，则模块七那条门槛
 * 要么静默失效（漏 OR），要么误伤（漏 AND），而两种错在「只测了拒绝路径」时都不明显。
 */
class TeachEligibilityCompositeTest {

    private static final long CHAT = -100900999L;
    private static final long USER = 777L;

    private static TeachGate passing() {
        return (chatId, userId) -> Optional.empty();
    }

    private static TeachGate rejecting(String reason) {
        return (chatId, userId) -> Optional.of(reason);
    }

    @Test
    void emptyGatesAllowEverything() {
        TeachEligibility eligibility = TeachEligibility.composite(List.of());

        assertThat(eligibility.rejectionFor(CHAT, USER)).isEmpty();
    }

    @Test
    void allGatesPassingAllows() {
        TeachEligibility eligibility = TeachEligibility.composite(List.of(passing(), passing()));

        assertThat(eligibility.rejectionFor(CHAT, USER)).isEmpty();
    }

    @Test
    void singleRejectionBlocks() {
        TeachEligibility eligibility = TeachEligibility.composite(List.of(passing(), rejecting("信用分不足")));

        assertThat(eligibility.rejectionFor(CHAT, USER)).contains("信用分不足");
    }

    @Test
    void everyRejectionIsReportedNotOnlyTheFirst() {
        TeachEligibility eligibility = TeachEligibility.composite(
                List.of(rejecting("有违规记录"), rejecting("信用分不足")));

        assertThat(eligibility.rejectionFor(CHAT, USER))
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("有违规记录")
                        .contains("信用分不足"));
    }

    @Test
    void forwardsArgumentsToEveryGate() {
        List<String> seen = new ArrayList<>();
        TeachGate recorder = (chatId, userId) -> {
            seen.add(chatId + ":" + userId);
            return Optional.empty();
        };

        TeachEligibility.composite(List.of(recorder, recorder)).rejectionFor(CHAT, USER);

        assertThat(seen).containsExactly(CHAT + ":" + USER, CHAT + ":" + USER);
    }
}
