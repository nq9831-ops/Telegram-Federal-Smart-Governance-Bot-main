package com.tg.heyisheng.bot.core.callback;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 按钮回调路由测试。
 *
 * <p>关键行为：命中处理器时分派；**未命中也要应答**——否则客户端按钮会一直转圈，
 * 用户会以为按钮坏了（这是回调入口最容易被忽略的一点）。
 */
class CallbackRouterTest {

    private static CallbackQuery query(String data) {
        // CallbackQuery 是 @NoArgsConstructor + @Setter（已核源码），没有 builder
        CallbackQuery query = new CallbackQuery();
        query.setId("cb-1");
        query.setData(data);
        return query;
    }

    @Test
    void routesToHandlerByActionPrefix() {
        CallbackHandler handler = new CallbackHandler() {
            @Override
            public String action() {
                return "verify";
            }

            @Override
            public Optional<BotApiMethod<?>> handle(CallbackQuery q) {
                return Optional.of(new SendMessage("1", "handled"));
            }
        };
        CallbackRouter router = new CallbackRouter(List.of(handler));

        assertThat(router.route(query("verify:-100:42")))
                .as("应按 action 前缀命中处理器")
                .isPresent()
                .get()
                .isInstanceOf(SendMessage.class);
    }

    @Test
    void answersEvenWhenNoHandlerMatches() {
        CallbackRouter router = new CallbackRouter(List.of());

        assertThat(router.route(query("stale-button:whatever")))
                .as("未命中也要应答，避免按钮一直转圈")
                .isPresent()
                .get()
                .isInstanceOf(AnswerCallbackQuery.class);
    }

    @Test
    void answersWhenDataIsMissingOrMalformed() {
        CallbackRouter router = new CallbackRouter(List.of());

        assertThat(router.route(query(null))).isPresent().get().isInstanceOf(AnswerCallbackQuery.class);
        assertThat(router.route(query(":payload"))).isPresent().get().isInstanceOf(AnswerCallbackQuery.class);
    }

    @Test
    void toleratesNullQuery() {
        assertThat(new CallbackRouter(List.of()).route(null)).isEmpty();
    }

    @Test
    void rejectsDuplicateAction() {
        // CallbackHandler 有两个方法，不是函数式接口，故用匿名类
        CallbackHandler a = handlerNamed("dup");
        CallbackHandler b = handlerNamed("dup");

        assertThatThrownBy(() -> new CallbackRouter(List.of(a, b)))
                .as("action 冲突应在装配期就失败，不留到运行期")
                .isInstanceOf(IllegalStateException.class);
    }

    private static CallbackHandler handlerNamed(String action) {
        return new CallbackHandler() {
            @Override
            public String action() {
                return action;
            }

            @Override
            public Optional<BotApiMethod<?>> handle(CallbackQuery q) {
                return Optional.empty();
            }
        };
    }

    @Test
    void actionOfExtractsPrefixBeforeSeparator() {
        assertThat(CallbackRouter.actionOf("verify:-100:42")).isEqualTo("verify");
        assertThat(CallbackRouter.actionOf("noargs")).isEqualTo("noargs");
        assertThat(CallbackRouter.actionOf("")).isNull();
        assertThat(CallbackRouter.actionOf(null)).isNull();
    }
}
