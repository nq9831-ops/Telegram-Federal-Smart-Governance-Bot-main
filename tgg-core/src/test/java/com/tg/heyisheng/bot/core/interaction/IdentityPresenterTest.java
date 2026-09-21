package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 身份展示的**隐私策略**（{@link IdentityPresenter}）——一处实现，两处共用（{@code /whoami} 与 {@code /menu} 身份行）。
 *
 * <p>钉住的核心不变量：**群内默认不出现明文用户 ID**，只有把开关打开才显示；
 * 私聊（一对一）永远显示。这条错了 = 把个人标识广播给全群，属隐私缺陷，故用测试钉死。
 */
class IdentityPresenterTest {

    /** 取一个足够独特的数字，避免断言命中文案里的偶然子串。 */
    private static final long GROUP = -1001234567890L;
    private static final long USER = 987654321L;

    private static IdentityPresenter presenter(boolean groupVisible) {
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        when(runtime.getBoolean(IdentityPresenter.GROUP_VISIBLE_KEY, false)).thenReturn(groupVisible);
        return new IdentityPresenter(runtime);
    }

    @Test
    void privateChatAlwaysShowsUserId() {
        // 私聊：Telegram 里 chatId 等于 userId
        String reply = presenter(false).whoamiReply(USER, USER);

        assertThat(reply).contains(String.valueOf(USER));
        assertThat(reply).as("私聊无需引导去私聊").doesNotContain("请私聊");
    }

    @Test
    void groupHidesUserIdByDefault() {
        String reply = presenter(false).whoamiReply(GROUP, USER);

        assertThat(reply).as("群内默认不得出现明文用户 ID").doesNotContain(String.valueOf(USER));
        assertThat(reply).as("应引导用户去私聊查看").contains("请私聊");
    }

    @Test
    void groupShowsUserIdAndChatIdWhenSwitchOn() {
        String reply = presenter(true).whoamiReply(GROUP, USER);

        assertThat(reply).contains(String.valueOf(USER)).contains(String.valueOf(GROUP));
    }

    @Test
    void menuLineShowsUserIdInPrivate() {
        assertThat(presenter(false).menuIdentityLine(USER, USER)).contains(String.valueOf(USER));
    }

    @Test
    void menuLineIsEmptyInGroupWhenHidden() {
        assertThat(presenter(false).menuIdentityLine(GROUP, USER))
                .as("群内默认不在面板里显示身份行（不引入可被转发的 ID）")
                .isEmpty();
    }

    @Test
    void menuLineShowsUserIdInGroupWhenSwitchOn() {
        assertThat(presenter(true).menuIdentityLine(GROUP, USER)).contains(String.valueOf(USER));
    }
}
