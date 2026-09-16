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
 * <p>重点：配置<b>真的挂到了上下文</b>（handler 才读得到），
 * 且中间件<b>在功能关闭时也照常放行</b>——开关判断归 {@code CommandDispatcher}，
 * 中间件若自行中断会让关闭态的群连恢复命令都进不来（见 doesNotInterruptEvenWhenGroupDisabled）。
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

    /**
     * 中间件<b>不再</b>因功能关闭而中断链——开关判断已移到命令级。
     *
     * <p>回归背景：早期实现在此处直接中断，导致被停用的群连 {@code /enable} 都进不来、
     * 永久锁死（实测暴露）。中间件看不到「即将执行哪条命令」，因此天生无法做这个判断。
     */
    @Test
    void doesNotInterruptEvenWhenGroupDisabled() {
        when(service.findOrDefault(CHAT_ID))
                .thenReturn(new GroupConfigView(CHAT_ID, "测试群", false));

        UpdateContext ctx = new UpdateContext(1, 42L, CHAT_ID, "/echo");

        assertThat(middleware.handle(ctx, chain))
                .as("中间件必须放行——是否拒绝由 CommandDispatcher 按命令判断")
                .isTrue();
    }

    /** 关闭的群也要挂载配置——分发器要靠它判断是否放行。 */
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
