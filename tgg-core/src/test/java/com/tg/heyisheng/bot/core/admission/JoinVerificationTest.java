package com.tg.heyisheng.bot.core.admission;

import com.tg.heyisheng.bot.common.util.IdHasher;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入群验证测试。
 *
 * <p>核心两条：新人入群确实发出带按钮的验证消息；**他人不能替本人点验证**
 * （按钮对全群可见，不校验点击者身份的话，验证形同虚设）。
 */
class JoinVerificationTest {

    private static final long CHAT = -100L;
    private static final long MEMBER = 42L;
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final IdHasher HASHER = IdHasher.fromEnvironment();

    /** 观察期服务，产出收集到 sent 里——用于断言"通过验证后确实施加了限制"。 */
    private static ObservationPeriodService observationInto(List<BotApiMethod<?>> sent) {
        return new ObservationPeriodService(sent::add, Duration.ofDays(7));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);

        void advance(Duration delta) {
            now.updateAndGet(cur -> cur.plus(delta));
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
            return now.get();
        }
    }

    private static User human(long id) {
        return User.builder().id(id).firstName("U").isBot(false).build();
    }

    private static Message joinMessage(long... memberIds) {
        List<User> members = new ArrayList<>();
        for (long id : memberIds) {
            members.add(human(id));
        }
        return Message.builder()
                .messageId(1)
                .chat(Chat.builder().id(CHAT).type("supergroup").build())
                .newChatMembers(members)
                .build();
    }

    private static CallbackQuery verifyClick(long clickerId, long chatId, long targetUserId) {
        CallbackQuery q = new CallbackQuery();
        q.setId("cb-1");
        q.setFrom(human(clickerId));
        // data 里是被验证者 userId 的哈希（与生产一致）
        q.setData("verify:" + chatId + ":" + HASHER.hash(targetUserId));
        return q;
    }

    @Test
    void joiningMemberGetsVerificationPromptWithButton() {
        PendingVerificationRegistry registry = new PendingVerificationRegistry(new MutableClock());
        List<BotApiMethod<?>> sent = new ArrayList<>();
        JoinVerificationService service = new JoinVerificationService(registry, sent::add, HASHER, TIMEOUT);

        service.onMembersJoined(joinMessage(MEMBER));

        assertThat(registry.isPending(CHAT, MEMBER)).as("必须登记待验证").isTrue();
        assertThat(sent).hasSize(1);
        SendMessage msg = (SendMessage) sent.get(0);
        assertThat(msg.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(msg.getReplyMarkup()).as("必须带内联按钮").isInstanceOf(InlineKeyboardMarkup.class);
    }

    /** 批量入群（广告号常用手法）时每人都要收到各自的验证消息——返回值只能带一个方法，故走主动通道。 */
    @Test
    void eachJoiningMemberGetsOwnPrompt() {
        PendingVerificationRegistry registry = new PendingVerificationRegistry(new MutableClock());
        List<BotApiMethod<?>> sent = new ArrayList<>();
        new JoinVerificationService(registry, sent::add, HASHER, TIMEOUT).onMembersJoined(joinMessage(42L, 43L));

        assertThat(sent).as("两名新成员应各收到一条").hasSize(2);
        assertThat(registry.isPending(CHAT, 42L)).isTrue();
        assertThat(registry.isPending(CHAT, 43L)).isTrue();
    }

    @Test
    void botMembersAreSkipped() {
        PendingVerificationRegistry registry = new PendingVerificationRegistry(new MutableClock());
        List<BotApiMethod<?>> sent = new ArrayList<>();
        Message message = Message.builder()
                .messageId(1)
                .chat(Chat.builder().id(CHAT).type("supergroup").build())
                .newChatMembers(List.of(User.builder().id(99L).firstName("Bot").isBot(true).build()))
                .build();

        new JoinVerificationService(registry, sent::add, HASHER, TIMEOUT).onMembersJoined(message);

        assertThat(sent).as("机器人入群不应触发验证").isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void memberCanVerifyThemselves() {
        PendingVerificationRegistry registry = new PendingVerificationRegistry(new MutableClock());
        registry.register(CHAT, MEMBER, TIMEOUT);
        VerificationCallbackHandler handler = new VerificationCallbackHandler(registry, HASHER, observationInto(new ArrayList<>()));

        Optional<BotApiMethod<?>> action = handler.handle(verifyClick(MEMBER, CHAT, MEMBER));

        assertThat(action).isPresent();
        assertThat(action.get()).isInstanceOf(AnswerCallbackQuery.class);
        assertThat(registry.isPending(CHAT, MEMBER)).as("验证通过后登记应被清除").isFalse();
    }

    /** 安全不变量：按钮对全群可见，他人不能替本人点。 */
    @Test
    void othersCannotVerifyOnBehalf() {
        PendingVerificationRegistry registry = new PendingVerificationRegistry(new MutableClock());
        registry.register(CHAT, MEMBER, TIMEOUT);
        VerificationCallbackHandler handler = new VerificationCallbackHandler(registry, HASHER, observationInto(new ArrayList<>()));

        Optional<BotApiMethod<?>> action = handler.handle(verifyClick(999L, CHAT, MEMBER));

        assertThat(action).isPresent();
        assertThat(((AnswerCallbackQuery) action.get()).getText())
                .as("应提示不是本人的按钮并拒绝").contains("不是你的");
        assertThat(registry.isPending(CHAT, MEMBER)).as("他人点击不得完成验证").isTrue();
    }

    @Test
    void expiredOrRepeatedClickIsRejected() {
        MutableClock clock = new MutableClock();
        PendingVerificationRegistry registry = new PendingVerificationRegistry(clock);
        registry.register(CHAT, MEMBER, TIMEOUT);
        VerificationCallbackHandler handler = new VerificationCallbackHandler(registry, HASHER, observationInto(new ArrayList<>()));

        clock.advance(TIMEOUT.plusSeconds(1));

        Optional<BotApiMethod<?>> action = handler.handle(verifyClick(MEMBER, CHAT, MEMBER));
        assertThat(action).isPresent();
        assertThat(((AnswerCallbackQuery) action.get()).getText())
                .as("过期点击应提示已失效").contains("过期");
    }

    @Test
    void malformedDataIsRejectedWithoutThrowing() {
        VerificationCallbackHandler handler =
                new VerificationCallbackHandler(new PendingVerificationRegistry(new MutableClock()), HASHER, observationInto(new ArrayList<>()));

        assertThat(handler.handle(verifyClick(MEMBER, CHAT, MEMBER)).get()).isInstanceOf(AnswerCallbackQuery.class);
        CallbackQuery bad = new CallbackQuery();
        bad.setId("cb-2");
        bad.setFrom(human(MEMBER));
        bad.setData("verify:notanumber:42");
        assertThat(handler.handle(bad)).isPresent();
    }

    /** 通过验证后必须进入观察期——否则"验证通过"就等于完全放行。 */
    @Test
    void successfulVerificationAppliesObservationPeriod() {
        PendingVerificationRegistry registry = new PendingVerificationRegistry(new MutableClock());
        registry.register(CHAT, MEMBER, TIMEOUT);
        List<BotApiMethod<?>> sent = new ArrayList<>();
        VerificationCallbackHandler handler =
                new VerificationCallbackHandler(registry, HASHER, observationInto(sent));

        handler.handle(verifyClick(MEMBER, CHAT, MEMBER));

        assertThat(sent).as("通过验证应施加观察期限制").hasSize(1);
        assertThat(sent.get(0)).isInstanceOf(RestrictChatMember.class);
    }

    /** 失败路径（他人冒点）不得对任何人施加限制——限制只属于通过验证的人。 */
    @Test
    void failedVerificationAppliesNoObservationPeriod() {
        PendingVerificationRegistry registry = new PendingVerificationRegistry(new MutableClock());
        registry.register(CHAT, MEMBER, TIMEOUT);
        List<BotApiMethod<?>> sent = new ArrayList<>();
        VerificationCallbackHandler handler =
                new VerificationCallbackHandler(registry, HASHER, observationInto(sent));

        handler.handle(verifyClick(999L, CHAT, MEMBER));

        assertThat(sent).as("他人冒点不得限制任何人").isEmpty();
    }

    /** 超时未验证者必须被移出——否则"验证"只是句空话。 */
    @Test
    void sweeperBansExpiredPendingMembers() {
        MutableClock clock = new MutableClock();
        PendingVerificationRegistry registry = new PendingVerificationRegistry(clock);
        registry.register(CHAT, MEMBER, TIMEOUT);
        List<BotApiMethod<?>> sent = new ArrayList<>();
        VerificationTimeoutSweeper sweeper = new VerificationTimeoutSweeper(registry, sent::add);

        clock.advance(TIMEOUT.plusSeconds(1));
        sweeper.sweep();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).isInstanceOf(BanChatMember.class);
        BanChatMember ban = (BanChatMember) sent.get(0);
        assertThat(ban.getChatId()).isEqualTo(String.valueOf(CHAT));
        assertThat(ban.getUserId()).isEqualTo(MEMBER);
        assertThat(registry.size()).as("移出后登记应清空").isZero();
    }

    /** 扫多了会误踢：尚未超时的待验证成员不能被移出。 */
    @Test
    void sweeperLeavesMembersWithinDeadline() {
        MutableClock clock = new MutableClock();
        PendingVerificationRegistry registry = new PendingVerificationRegistry(clock);
        registry.register(CHAT, MEMBER, TIMEOUT);
        List<BotApiMethod<?>> sent = new ArrayList<>();

        new VerificationTimeoutSweeper(registry, sent::add).sweep();

        assertThat(sent).as("未超时不应移出").isEmpty();
        assertThat(registry.isPending(CHAT, MEMBER)).isTrue();
    }
}
