package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /whoami} 的行为与元数据。
 *
 * <p>两条硬约束：① 群内默认**不暴露明文用户 ID**（隐私，见 {@code PRIVACY.md} 全群可见口径）；
 * ② 它是 {@code publicCommand}——否则 {@code CommandMenuContentTest} 的 fail-closed 完整性
 * 会判它「不属于任何入口、对谁都不可见」。
 */
class WhoamiCommandHandlerTest {

    private static final long GROUP = -1001234567890L;
    private static final long USER = 987654321L;

    private static WhoamiCommandHandler handler(boolean groupVisible) {
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        when(runtime.getBoolean(IdentityPresenter.GROUP_VISIBLE_KEY, false)).thenReturn(groupVisible);
        return new WhoamiCommandHandler(new IdentityPresenter(runtime));
    }

    private static String textOf(WhoamiCommandHandler handler, long chatId, long userId) {
        SendMessage reply = (SendMessage) handler.handle(new UpdateContext(1, userId, chatId, "whoami"));
        return reply.getText();
    }

    @Test
    void privateChatRevealsUserId() {
        assertThat(textOf(handler(false), USER, USER)).contains(String.valueOf(USER));
    }

    @Test
    void groupHidesUserIdUnlessSwitchedOn() {
        assertThat(textOf(handler(false), GROUP, USER))
                .as("群内默认不得出现明文用户 ID").doesNotContain(String.valueOf(USER));
        assertThat(textOf(handler(true), GROUP, USER))
                .contains(String.valueOf(USER)).contains(String.valueOf(GROUP));
    }

    @Test
    void repliesToTheCurrentChat() {
        SendMessage reply = (SendMessage) handler(false).handle(new UpdateContext(1, USER, GROUP, "whoami"));

        assertThat(reply.getChatId()).isEqualTo(String.valueOf(GROUP));
    }

    @Test
    void commandIsPublicAndSelfService() {
        BotCommand meta = WhoamiCommandHandler.class.getAnnotation(BotCommand.class);

        assertThat(meta).as("@BotCommand 必须声明").isNotNull();
        assertThat(meta.value()).isEqualTo("whoami");
        assertThat(meta.publicCommand()).as("自助命令须对全体成员可见，才能经 /menu 到达").isTrue();
        assertThat(meta.description()).as("缺描述会从客户端菜单静默消失").isNotBlank();
        assertThat(meta.category()).isEqualTo(MenuCategory.SELF_SERVICE);
    }
}
