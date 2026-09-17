package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfig;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigRepository;
import com.tg.heyisheng.bot.credit.CreditPenaltyOrder;
import com.tg.heyisheng.bot.credit.PenaltyType;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 跨群封禁的单测：<b>只封启用群、不漏不误</b>，且非个人维度不被误处理。
 */
class FederationBanServiceTest {

    private final GroupConfigRepository repository = mock(GroupConfigRepository.class);
    private final List<BotApiMethod<?>> sent = new ArrayList<>();
    private final FederationBanService service = new FederationBanService(repository, sent::add);

    private static GroupConfig group(long chatId, boolean enabled) {
        GroupConfig config = new GroupConfig(chatId, "群" + chatId);
        config.setEnabled(enabled);
        return config;
    }

    private static CreditPenaltyOrder order(CreditSubjectType type, long subjectId) {
        return new CreditPenaltyOrder("order-1", type, subjectId,
                PenaltyType.REPORT_TO_FEDERATION, Instant.now(), "sig");
    }

    @Test
    void bansSubjectInEveryEnabledGroupOnly() {
        when(repository.findAll()).thenReturn(List.of(
                group(-100L, true),
                group(-200L, false),   // 已停用 → 不封
                group(-300L, true)));

        service.onPenaltyAccepted(order(CreditSubjectType.INDIVIDUAL, 42L));

        assertThat(sent).as("只对启用的群下发封禁").hasSize(2);
        assertThat(sent).allSatisfy(m -> assertThat(m).isInstanceOf(BanChatMember.class));
        assertThat(sent.stream().map(m -> ((BanChatMember) m).getChatId()).toList())
                .containsExactlyInAnyOrder("-100", "-300");
        assertThat(sent.stream().map(m -> ((BanChatMember) m).getUserId()).distinct().toList())
                .as("封的是被处罚主体").containsExactly(42L);
    }

    @Test
    void ignoresNonIndividualSubjects() {
        when(repository.findAll()).thenReturn(List.of(group(-100L, true)));

        service.onPenaltyAccepted(order(CreditSubjectType.GROUP, -999L));

        assertThat(sent).as("群组/商家维度的处罚不当作'封人'处理").isEmpty();
    }

    @Test
    void noKnownGroupsMeansNothingToDo() {
        when(repository.findAll()).thenReturn(List.of());

        service.onPenaltyAccepted(order(CreditSubjectType.INDIVIDUAL, 42L));

        assertThat(sent).isEmpty();
    }
}
