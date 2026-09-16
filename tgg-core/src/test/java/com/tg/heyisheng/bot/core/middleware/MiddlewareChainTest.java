package com.tg.heyisheng.bot.core.middleware;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中间件链测试：重点是<b>顺序</b>与<b>中断</b>语义，而非单个中间件的内部逻辑。
 */
class MiddlewareChainTest {

    private static final UpdateContext CTX = new UpdateContext(1, 100L, -200L, "/echo");

    @Test
    void executesMiddlewaresInRegistrationOrder() {
        List<String> trace = new ArrayList<>();

        MiddlewareChain chain = new MiddlewareChain(List.of(
                recording("first", trace, true),
                recording("second", trace, true),
                recording("third", trace, true)));

        assertThat(chain.proceed(CTX)).isTrue();
        assertThat(trace).containsExactly("first", "second", "third");
    }

    @Test
    void stopsAtFirstMiddlewareReturningFalse() {
        List<String> trace = new ArrayList<>();

        MiddlewareChain chain = new MiddlewareChain(List.of(
                recording("first", trace, true),
                recording("second", trace, false),
                recording("third", trace, true)));

        assertThat(chain.proceed(CTX)).isFalse();
        assertThat(trace).as("中断后不得继续执行后续中间件").containsExactly("first", "second");
    }

    @Test
    void emptyChainPasses() {
        assertThat(new MiddlewareChain(List.of()).proceed(CTX)).isTrue();
    }

    @Test
    void authenticationMiddlewareRejectsContextWithoutSender() {
        AuthenticationMiddleware auth = new AuthenticationMiddleware();
        MiddlewareChain chain = new MiddlewareChain(List.of());

        assertThat(auth.handle(CTX, chain)).isTrue();
        assertThat(auth.handle(new UpdateContext(1, null, -200L, null), chain)).isFalse();
    }

    @Test
    void permissionMiddlewareKnowsConfiguredAdmins() {
        PermissionMiddleware pm = new PermissionMiddleware(Set.of(42L));

        assertThat(pm.isAdmin(42L)).isTrue();
        assertThat(pm.isAdmin(7L)).isFalse();
        assertThat(pm.isAdmin(null)).isFalse();
    }

    private static Middleware recording(String name, List<String> trace, boolean result) {
        return (ctx, chain) -> {
            trace.add(name);
            return result;
        };
    }
}
