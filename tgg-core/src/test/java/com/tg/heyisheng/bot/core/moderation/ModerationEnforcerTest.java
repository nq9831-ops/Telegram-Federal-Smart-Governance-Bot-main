package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 处置执行器测试。
 *
 * <p>存在意义：判定结果若无消费者，审核在功能上等于零——
 * 本项目已在 RBAC 与功能开关上栽过同一个坑（「接好了但没通电」），
 * 本类是该模式在审核链路上的护栏。
 */
class ModerationEnforcerTest {

    private static final long CHAT_ID = -100L;
    private static final int MESSAGE_ID = 55;

    private final ModerationEnforcer enforcer = new ModerationEnforcer();

    @Test
    void deletesMessageWhenVerdictNeedsReview() {
        UpdateContext ctx = ctxWithVerdict(new ModerationVerdict(RiskLevel.LOW, false, List.of("SPAM_CASINO")));

        Optional<BotApiMethod<?>> action = enforcer.enforce(ctx);

        assertThat(action).isPresent();
        assertThat(action.get()).isInstanceOf(DeleteMessage.class);
        DeleteMessage delete = (DeleteMessage) action.get();
        assertThat(delete.getChatId()).as("必须定位到正确的群").isEqualTo(String.valueOf(CHAT_ID));
        assertThat(delete.getMessageId()).as("必须定位到正确的消息").isEqualTo(MESSAGE_ID);
    }

    @Test
    void doesNothingForCleanContent() {
        UpdateContext ctx = ctxWithVerdict(ModerationVerdict.clean());

        assertThat(enforcer.enforce(ctx)).isEmpty();
    }

    /** 未挂载判定（未启用审核）时不得处置——否则会把未审内容当成违规删掉。 */
    @Test
    void doesNothingWhenNoVerdictAttached() {
        UpdateContext ctx = new UpdateContext(1, 42L, CHAT_ID, MESSAGE_ID, null);

        assertThat(enforcer.enforce(ctx)).isEmpty();
    }

    /** 硬红线同样走删除——删除是保底动作，任何情况下都必须立刻生效。 */
    @Test
    void hardLineIsAlsoDeletedImmediately() {
        UpdateContext ctx = ctxWithVerdict(
                new ModerationVerdict(RiskLevel.HIGH, true, List.of("HARD_SECRET_PHRASE")));

        assertThat(enforcer.enforce(ctx)).isPresent();
    }

    /**
     * 硬红线封禁：webhook 一次只能返回一个方法，故删除作响应体、封禁走主动通道。
     *
     * <p>这是「只删不冻」缺口的护栏——旧实现下硬红线只返回删除、从不封禁，
     * 而旧的 hardLineIsAlsoDeletedImmediately 只断言 present，盖不住这个洞。
     */
    @Test
    void bansPublisherOnHardLine() {
        List<BotApiMethod<?>> sent = new ArrayList<>();
        ModerationEnforcer banningEnforcer = new ModerationEnforcer(sent::add);
        UpdateContext ctx = ctxWithVerdict(
                new ModerationVerdict(RiskLevel.HIGH, true, List.of("HARD_SECRET_PHRASE")));

        Optional<BotApiMethod<?>> action = banningEnforcer.enforce(ctx);

        assertThat(action).as("删除仍作为返回值保底").isPresent();
        assertThat(action.get()).isInstanceOf(DeleteMessage.class);

        List<BotApiMethod<?>> bans = ofType(sent, BanChatMember.class);
        assertThat(bans).as("硬红线必须经主动通道封禁发布者").hasSize(1);
        BanChatMember ban = (BanChatMember) bans.get(0);
        assertThat(ban.getChatId()).as("封禁须定位到正确群").isEqualTo(String.valueOf(CHAT_ID));
        assertThat(ban.getUserId()).as("封禁须定位到发布者").isEqualTo(42L);
        assertThat(ofType(sent, SendMessage.class))
                .as("封禁也要说清——不让它成为无声消失").hasSize(1);
    }

    /** 非硬红线不得封禁——封禁是硬红线的专属处置。 */
    @Test
    void doesNotBanOnNonHardLine() {
        List<BotApiMethod<?>> sent = new ArrayList<>();
        ModerationEnforcer banningEnforcer = new ModerationEnforcer(sent::add);
        UpdateContext ctx = ctxWithVerdict(
                new ModerationVerdict(RiskLevel.LOW, false, List.of("SPAM_CASINO")));

        banningEnforcer.enforce(ctx);

        assertThat(ofType(sent, BanChatMember.class)).as("轻微/中风险不封禁").isEmpty();
        assertThat(ofType(sent, SendMessage.class)).as("但必须群内告知「为什么被删」").hasSize(1);
    }

    /** 缺少 userId 时无法定位发布者：删除仍生效，但不得封禁、更不得抛异常。 */
    @Test
    void doesNotBanWhenUserIdMissingButStillDeletes() {
        List<BotApiMethod<?>> sent = new ArrayList<>();
        ModerationEnforcer banningEnforcer = new ModerationEnforcer(sent::add);
        UpdateContext ctx = new UpdateContext(1, null, CHAT_ID, MESSAGE_ID, null);
        ctx.attach(new ModerationVerdict(RiskLevel.HIGH, true, List.of("HARD_SECRET_PHRASE")));

        assertThat(banningEnforcer.enforce(ctx)).isPresent();
        assertThat(ofType(sent, BanChatMember.class)).as("缺 userId 时无法定位发布者，不得封禁").isEmpty();
    }

    /**
     * 删除必须伴随**群内告知**——此前是静默删除，用户只看到消息莫名消失、不知道触了什么规则。
     * 这是 V5.0 轻度「删除+警告」里的「警告」。
     */
    @Test
    void notifiesGroupWhyTheMessageWasDeleted() {
        List<BotApiMethod<?>> sent = new ArrayList<>();
        ModerationEnforcer notifying = new ModerationEnforcer(sent::add);

        notifying.enforce(ctxWithVerdict(
                new ModerationVerdict(RiskLevel.LOW, false, List.of("SPAM_CASINO"))));

        assertThat(sent).hasSize(1);
        SendMessage notice = (SendMessage) sent.get(0);
        assertThat(notice.getChatId()).as("告知发在发生违规的群里").isEqualTo(String.valueOf(CHAT_ID));
        assertThat(notice.getText()).isEqualTo(ModerationEnforcer.DELETED_NOTICE);
        assertThat(notice.getText())
                .as("不透露命中的具体规则——那等于把规则库交给想绕过的试探者")
                .doesNotContain("SPAM_CASINO");
    }

    /** 硬红线用另一条文案，把「封禁」这个额外后果一并说清。 */
    @Test
    void hardLineNoticeExplainsTheBan() {
        List<BotApiMethod<?>> sent = new ArrayList<>();

        new ModerationEnforcer(sent::add).enforce(ctxWithVerdict(
                new ModerationVerdict(RiskLevel.HIGH, true, List.of("HARD_SECRET_PHRASE"))));

        assertThat(ofType(sent, SendMessage.class))
                .extracting(m -> ((SendMessage) m).getText())
                .containsExactly(ModerationEnforcer.FROZEN_NOTICE);
    }

    /** 缺少 messageId 时无法定位目标：应放弃处置而非抛异常中断整条链路。 */
    @Test
    void skipsWhenMessageIdMissing() {
        UpdateContext ctx = new UpdateContext(1, 42L, CHAT_ID, null, null);
        ctx.attach(new ModerationVerdict(RiskLevel.HIGH, false, List.of("SPAM_CASINO")));

        assertThat(enforcer.enforce(ctx)).isEmpty();
    }

    @Test
    void toleratesNullContext() {
        assertThat(enforcer.enforce(null)).isEmpty();
    }

    private static UpdateContext ctxWithVerdict(ModerationVerdict verdict) {
        UpdateContext ctx = new UpdateContext(1, 42L, CHAT_ID, MESSAGE_ID, null);
        ctx.attach(verdict);
        return ctx;
    }

    /** 按方法类型筛选主动通道发出的动作——断言「不许有封禁」时不能简写成「列表为空」。 */
    private static List<BotApiMethod<?>> ofType(List<BotApiMethod<?>> sent, Class<?> type) {
        return sent.stream().filter(type::isInstance).toList();
    }
}
