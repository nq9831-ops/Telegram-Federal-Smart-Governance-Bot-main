package com.tg.heyisheng.bot;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.telegram.telegrambots.meta.api.objects.EntityType;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 功能开关的<b>端到端</b>测试：走完整装配（中间件链 + 命令分发 + 真实 MySQL）。
 *
 * <p>为什么需要它：{@code ConfigSwitchCommandsTest} 用手工 {@code attach} 构造上下文，
 * 绕过了 {@code GroupConfigMiddleware}——那个位置恰恰是锁死缺陷的发生地。
 * 只测命令级判定无法证明「经真实装配链路后 /disable 与 /enable 都能工作」。
 *
 * <p>本测试覆盖完整的往复：开启 → 关闭 → 关闭态普通命令被拒 → 关闭态 /enable 仍可用 → 恢复。
 * 最后一步是锁死缺陷的回归点：早期实现在中间件里中断链，那一步会拿不到任何回复。
 */
@SpringBootTest
class ConfigSwitchEndToEndIT {

    private static final long CHAT_ID = -777003L;
    private static final long ADMIN_USER = 42L;

    @Autowired
    private UpdateDispatcher updateDispatcher;

    @Autowired
    private GroupConfigService groupConfigService;

    /** 自建前置：确保起始为启用态（测试直连真实库，不假设既有状态）。 */
    @BeforeEach
    void startFromEnabled() {
        groupConfigService.setEnabled(CHAT_ID, true);
    }

    @Test
    void fullRoundTripThroughRealChain() throws Exception {
        // 1. 关闭
        assertThat(dispatch("/disable")).as("管理类命令应可用").isPresent();
        assertThat(groupConfigService.findOrDefault(CHAT_ID).enabled())
                .as("关闭必须真的写入数据库").isFalse();

        // 2. 关闭态下普通命令被拒（开关生效）
        assertThat(dispatch("/echo")).as("关闭态下普通命令不得执行").isEmpty();

        // 3. 关闭态下 /enable 仍必须可用 —— 锁死缺陷的回归点
        assertThat(dispatch("/enable"))
                .as("关闭态下 /enable 必须可用，否则该群永久锁死")
                .isPresent();
        assertThat(groupConfigService.findOrDefault(CHAT_ID).enabled()).isTrue();

        // 4. 恢复后普通命令可用
        assertThat(dispatch("/echo")).as("恢复后普通命令应可用").isPresent();
    }

    /** 普通成员不得翻转开关（权限门控在完整链路上同样生效）。 */
    @Test
    void ordinaryMemberCannotFlipThroughRealChain() throws Exception {
        assertThat(dispatch("/disable", 999L)).isEmpty();

        assertThat(groupConfigService.findOrDefault(CHAT_ID).enabled())
                .as("被拒的请求不得写入数据库")
                .isTrue();
    }

    private java.util.Optional<org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?>>
            dispatch(String command) throws Exception {
        return dispatch(command, ADMIN_USER);
    }

    private java.util.Optional<org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?>>
            dispatch(String command, long userId) throws Exception {
        return updateDispatcher.dispatch(update(command, userId));
    }

    private static Update update(String commandText, long userId) {
        MessageEntity entity = MessageEntity.builder()
                .type(EntityType.BOTCOMMAND)
                .offset(0)
                .length(commandText.length())
                .build();

        Message message = Message.builder()
                .text(commandText)
                .entities(List.of(entity))
                .chat(Chat.builder().id(CHAT_ID).type("supergroup").build())
                .from(User.builder().id(userId).firstName("T").isBot(false).build())
                .build();

        Update update = new Update();
        update.setUpdateId(1);
        update.setMessage(message);
        return update;
    }
}
