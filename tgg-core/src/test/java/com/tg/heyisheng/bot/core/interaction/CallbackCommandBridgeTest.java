package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandDispatcher;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigView;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.PermissionChecker;
import com.tg.heyisheng.bot.core.permission.Role;
import com.tg.heyisheng.bot.core.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回调→命令桥的行为测试。
 *
 * <p><b>为什么用真实 {@code CommandRegistry} + {@code CommandDispatcher}</b>：桥的全部价值就是
 * 「把按钮点击交给与文本命令<b>同一个</b>门控」，mock 掉门控层等于把被测的契约替换成了假设。
 * 本测试因此只替换「群配置来源」与「限流器」，门控判定走真家伙。
 *
 * <p><b>安全不变量</b>（逐条对应计划 §6）：
 * S1 门控只走 CommandDispatcher；S2 必须显式加载群配置；S5 必须限流；S6 命令名必须在注册表。
 */
class CallbackCommandBridgeTest {

    private static final long CHAT = -100L;
    private static final long USER = 42L;

    /** 被调用的次数即「命令是否真的执行了」的判据。 */
    static final AtomicInteger CALLS = new AtomicInteger();

    @BotCommand(value = "words", description = "查看词表", requiredPermission = Permission.MANAGE_CONFIG)
    static class WordsHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            CALLS.incrementAndGet();
            return new SendMessage(String.valueOf(ctx.chatId()), "本群违禁词：");
        }
    }

    /** 始终放行的限流器。 */
    private static final RateLimiter ALLOW = key -> true;
    /** 始终拒绝的限流器。 */
    private static final RateLimiter DENY = key -> false;

    private final GroupConfigService groupConfigs = mock(GroupConfigService.class);

    private CallbackCommandBridge bridge(String adminsRole, boolean groupEnabled, RateLimiter limiter) {
        // 走真实注册表 + 真实分发器：门控不是 mock
        CommandRegistry registry = new CommandRegistry(List.of(new WordsHandler()));
        Role role = Role.valueOf(adminsRole);
        CommandDispatcher dispatcher = new CommandDispatcher(registry,
                new PermissionChecker(role));
        when(groupConfigs.findOrDefault(CHAT))
                .thenReturn(new GroupConfigView(CHAT, "测试群", groupEnabled));
        return new CallbackCommandBridge(dispatcher, registry, groupConfigs, limiter, null);
    }

    private static CallbackQuery click(String data) {
        CallbackQuery q = new CallbackQuery();
        q.setId("cb-1");
        q.setFrom(User.builder().id(USER).firstName("T").isBot(false).build());
        q.setData(data);
        return q;
    }

    @org.junit.jupiter.api.BeforeEach
    void resetCalls() {
        CALLS.set(0);
    }

    /** 基线：授权用户 + 群启用 → 命令真的执行了（否则下面几条「没执行」的断言毫无意义）。 */
    @Test
    void executesCommandForAuthorizedUser() {
        Optional<BotApiMethod<?>> result = bridge("ADMIN", true, ALLOW)
                .execute(click("menu:" + CHAT + ":words"), CHAT, "words", null, false);

        assertThat(result).as("授权用户点按钮应产出回复").isPresent();
        assertThat(CALLS.get()).as("命令必须真的被执行").isEqualTo(1);
    }

    /** S1：权限判定复用 CommandDispatcher —— 无权用户不得执行。 */
    @Test
    void deniesUserWithoutPermission() {
        Optional<BotApiMethod<?>> result = bridge("MEMBER", true, ALLOW)
                .execute(click("menu:" + CHAT + ":words"), CHAT, "words", null, false);

        assertThat(CALLS.get()).as("无权时命令不得执行").isZero();
        assertThat(result).as("无结果时至少回一个应答，否则按钮一直转圈")
                .hasValueSatisfying(method -> assertThat(method).isInstanceOf(AnswerCallbackQuery.class));
    }

    /**
     * S2（本设计最易踩的坑）：群配置必须由桥显式加载并挂到上下文。
     * 漏了它，CommandDispatcher.isGroupEnabled 的 orElse(true) 会把停用群当成启用 → 按钮照样执行。
     */
    @Test
    void respectsDisabledGroup() {
        Optional<BotApiMethod<?>> result = bridge("ADMIN", false, ALLOW)
                .execute(click("menu:" + CHAT + ":words"), CHAT, "words", null, false);

        assertThat(CALLS.get()).as("停用群里按钮不得成为绕过开关的通道").isZero();
        assertThat(result).as("无结果时至少回一个应答")
                .hasValueSatisfying(method -> assertThat(method).isInstanceOf(AnswerCallbackQuery.class));
    }

    /** S6：data 里的命令名必须在注册表内，防伪造 data 注入任意命令。 */
    @Test
    void rejectsUnknownCommand() {
        Optional<BotApiMethod<?>> result = bridge("ADMIN", true, ALLOW)
                .execute(click("menu:" + CHAT + ":eval"), CHAT, "eval", null, false);

        assertThat(result).as("未知命令应回一句提示").isPresent();
        assertThat(CALLS.get()).isZero();
    }

    /** S5：按钮是新的可刷入口，必须限流。 */
    @Test
    void rejectsWhenRateLimited() {
        Optional<BotApiMethod<?>> result = bridge("ADMIN", true, DENY)
                .execute(click("menu:" + CHAT + ":words"), CHAT, "words", null, false);

        assertThat(result).as("超限应回提示而不是静默").isPresent();
        assertThat(CALLS.get()).as("超限时命令不得执行").isZero();
    }

    /** 点击者身份缺失（异常回调）→ 不猜、不执行。 */
    @Test
    void rejectsWhenClickerUnknown() {
        CallbackQuery anonymous = new CallbackQuery();
        anonymous.setId("cb-2");
        anonymous.setData("menu:" + CHAT + ":words");

        Optional<BotApiMethod<?>> result = bridge("ADMIN", true, ALLOW)
                .execute(anonymous, CHAT, "words", null, false);

        assertThat(result).isEmpty();
        assertThat(CALLS.get()).isZero();
    }
}
