package com.tg.heyisheng.bot.core.middleware;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 群组配置中间件测试。
 *
 * <p>重点两条：配置<b>真的挂到了上下文</b>（handler 才读得到）、
 * 功能开关为关闭时<b>真的中断链路</b>。
 */
class GroupConfigMiddlewareTest {

    private static final long CHAT_ID = -100L;

    private final GroupConfigService service = mock(GroupConfigService.class);
    private final GroupConfigMiddleware middleware = new GroupConfigMiddleware(service);
    private final MiddlewareChain chain = new MiddlewareChain(List.of());

    @Test
    void attachesLoadedConfigToContext() {
        GroupConfigView config = new GroupConfigView(CHAT_ID, "测试群", true);
        when(service.findOrDefault(CHAT_ID)).thenReturn(config);

        UpdateContext ctx = new UpdateContext(1, 42L, CHAT_ID, "/echo");

        assertThat(middleware.handle(ctx, chain)).isTrue();
        assertThat(ctx.find(GroupConfigView.class))
                .as("配置必须挂到上下文，否则 handler 读不到（单实例贯通的用途所在）")
                .contains(config);
    }

    @Test
    void disabledGroupInterruptsTheChain() {
        when(service.findOrDefault(CHAT_ID))
                .thenReturn(new GroupConfigView(CHAT_ID, "测试群", false));

        UpdateContext ctx = new UpdateContext(1, 42L, CHAT_ID, "/echo");

        assertThat(middleware.handle(ctx, chain))
                .as("功能开关关闭的群不得继续处理")
                .isFalse();
    }

    /** 关闭的群也要挂载配置——否则后续若要做"为什么没响应"的解释就拿不到依据。 */
    @Test
    void disabledGroupStillAttachesConfig() {
        GroupConfigView config = new GroupConfigView(CHAT_ID, "测试群", false);
        when(service.findOrDefault(CHAT_ID)).thenReturn(config);

        UpdateContext ctx = new UpdateContext(1, 42L, CHAT_ID, "/echo");
        middleware.handle(ctx, chain);

        assertThat(ctx.find(GroupConfigView.class)).contains(config);
    }

    @Test
    void enabledGroupPassesThrough() {
        when(service.findOrDefault(CHAT_ID))
                .thenReturn(new GroupConfigView(CHAT_ID, "测试群", true));

        assertThat(middleware.handle(new UpdateContext(1, 42L, CHAT_ID, "/echo"), chain)).isTrue();
    }
}
