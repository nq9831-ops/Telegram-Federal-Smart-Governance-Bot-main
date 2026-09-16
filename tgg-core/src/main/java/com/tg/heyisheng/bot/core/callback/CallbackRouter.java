package com.tg.heyisheng.bot.core.callback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按钮回调路由：按 {@code data} 的 action 前缀分派给 {@link CallbackHandler}。
 *
 * <p>与 {@code CommandRegistry} 同构（启动期建立映射、冲突即失败），但更简单——
 * 回调是 UI 触发的封闭集合，不涉及权限与开关。
 *
 * <p><b>未命中也要应答</b>：若 data 没有对应处理器（例如用户点了一个早已失效的旧按钮），
 * 仍返回一个 {@link AnswerCallbackQuery}——否则客户端会一直转圈，用户以为按钮坏了。
 */
public class CallbackRouter {

    private static final Logger log = LoggerFactory.getLogger(CallbackRouter.class);
    private static final char SEPARATOR = ':';

    private final Map<String, CallbackHandler> handlers;

    public CallbackRouter(List<CallbackHandler> handlers) {
        Map<String, CallbackHandler> map = new HashMap<>();
        for (CallbackHandler handler : handlers == null ? List.<CallbackHandler>of() : handlers) {
            CallbackHandler existing = map.putIfAbsent(handler.action(), handler);
            if (existing != null) {
                throw new IllegalStateException("回调 action 冲突：" + handler.action());
            }
        }
        this.handlers = Map.copyOf(map);
    }

    /**
     * 路由一个回调。
     *
     * @return 要执行的 Bot API 方法（命中处理器时为其返回值；未命中时为一个"无提示"的应答）
     */
    public Optional<BotApiMethod<?>> route(CallbackQuery query) {
        if (query == null) {
            return Optional.empty();
        }
        String action = actionOf(query.getData());
        CallbackHandler handler = action == null ? null : handlers.get(action);
        if (handler == null) {
            log.debug("回调无处理器，仅应答：data={}", query.getData());
            return Optional.of(AnswerCallbackQuery.builder().callbackQueryId(query.getId()).build());
        }
        return handler.handle(query);
    }

    /** 取 action 前缀；无 data 或格式非法时返回 null。 */
    static String actionOf(String data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        int sep = data.indexOf(SEPARATOR);
        String action = sep < 0 ? data : data.substring(0, sep);
        return action.isEmpty() ? null : action;
    }

    /** 便于测试与诊断。 */
    public int size() {
        return handlers.size();
    }
}
