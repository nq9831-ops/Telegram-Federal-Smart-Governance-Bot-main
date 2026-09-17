package com.tg.heyisheng.bot.core.moderation;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.UnbanChatMember;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复核裁决服务单测（模块九 §10.4 · 操作员推翻权）。
 *
 * <p><b>本类守三件事</b>：
 * <ol>
 *   <li><b>推翻要真解封</b>——硬红线误封是「误封真人」，推翻必须发出解封动作，
 *       否则推翻权只是改了个状态字段（本项目反复警惕的「接好了但没通电」）；</li>
 *   <li><b>幂等</b>——已终态不再改动、更不重复处置（重复解封/重复禁言都是事故）；</li>
 *   <li><b>不越权处置</b>——非硬红线的推翻、MEDIUM 的维持都不该产生 Telegram 动作
 *       （没有封禁可撤销 / 不该加码）。</li>
 * </ol>
 */
class ModerationReviewDecisionServiceTest {

    private static final long ID = 7L;
    private static final long CHAT = -100900500L;
    private static final long USER = 4242L;
    private static final long OPERATOR = 999L;
    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

    private final ModerationReviewRepository repository = mock(ModerationReviewRepository.class);
    private final List<BotApiMethod<?>> sent = new ArrayList<>();
    private final ModerationActionSender sender = sent::add;

    private ModerationReviewDecisionService service() {
        return new ModerationReviewDecisionService(repository, sender, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ModerationReviewItem item(RiskLevel level, boolean hardLine) {
        return new ModerationReviewItem(CHAT, USER, 77, List.of("R1"), level, hardLine);
    }

    @Test
    void rejectOverturnsHardLineBanByUnbanning() {
        ModerationReviewItem i = item(RiskLevel.HIGH, true);
        when(repository.findById(ID)).thenReturn(Optional.of(i));

        ModerationReviewDecisionService.Outcome outcome =
                service().decide(ID, ReviewStatus.REJECTED, OPERATOR, "误报");

        assertThat(outcome.result()).isEqualTo(ModerationReviewDecisionService.Outcome.Result.DECIDED);
        assertThat(i.getStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(i.getDecidedBy()).isEqualTo(OPERATOR);
        assertThat(i.getDecidedAt()).isEqualTo(NOW);
        assertThat(i.getNote()).isEqualTo("误报");

        assertThat(sent).as("推翻硬红线误封必须真的发出解封动作").hasSize(1);
        assertThat(sent.get(0)).isInstanceOf(UnbanChatMember.class);
        UnbanChatMember unban = (UnbanChatMember) sent.get(0);
        assertThat(String.valueOf(unban.getChatId())).isEqualTo(String.valueOf(CHAT));
        assertThat(unban.getUserId()).isEqualTo(USER);
    }

    @Test
    void rejectNonHardLineTakesNoTelegramAction() {
        ModerationReviewItem i = item(RiskLevel.MEDIUM, false);
        when(repository.findById(ID)).thenReturn(Optional.of(i));

        service().decide(ID, ReviewStatus.REJECTED, OPERATOR, null);

        assertThat(i.getStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(sent).as("非硬红线本无封禁，推翻无动作可撤销").isEmpty();
    }

    @Test
    void approveHighMutesPublisher() {
        ModerationReviewItem i = item(RiskLevel.HIGH, false);
        when(repository.findById(ID)).thenReturn(Optional.of(i));

        service().decide(ID, ReviewStatus.APPROVED, OPERATOR, "确认");

        assertThat(i.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(sent).as("维持 HIGH 违规应追加禁言").hasSize(1);
        assertThat(sent.get(0)).isInstanceOf(RestrictChatMember.class);
    }

    @Test
    void approveMediumTakesNoTelegramAction() {
        ModerationReviewItem i = item(RiskLevel.MEDIUM, false);
        when(repository.findById(ID)).thenReturn(Optional.of(i));

        service().decide(ID, ReviewStatus.APPROVED, OPERATOR, null);

        assertThat(sent).as("MEDIUM 维持只落结论，不加码处置").isEmpty();
    }

    @Test
    void decideIsIdempotentAndDoesNotRepeatEnforcement() {
        ModerationReviewItem i = item(RiskLevel.HIGH, true);
        i.decide(ReviewStatus.REJECTED, 1L, null, NOW);
        when(repository.findById(ID)).thenReturn(Optional.of(i));

        ModerationReviewDecisionService.Outcome outcome =
                service().decide(ID, ReviewStatus.APPROVED, OPERATOR, null);

        assertThat(outcome.result())
                .isEqualTo(ModerationReviewDecisionService.Outcome.Result.ALREADY_DECIDED);
        assertThat(outcome.status()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(i.getStatus()).as("终态不可被改写").isEqualTo(ReviewStatus.REJECTED);
        assertThat(sent).as("重复裁决不得二次处置").isEmpty();
    }

    @Test
    void decideUnknownIdReportsNotFound() {
        when(repository.findById(ID)).thenReturn(Optional.empty());

        assertThat(service().decide(ID, ReviewStatus.APPROVED, OPERATOR, null).result())
                .isEqualTo(ModerationReviewDecisionService.Outcome.Result.NOT_FOUND);
        assertThat(sent).isEmpty();
    }

    @Test
    void listPendingDelegatesToRepository() {
        when(repository.findByStatusOrderByIdAsc(ReviewStatus.PENDING)).thenReturn(List.of());

        assertThat(service().listPending()).isEmpty();
    }
}
