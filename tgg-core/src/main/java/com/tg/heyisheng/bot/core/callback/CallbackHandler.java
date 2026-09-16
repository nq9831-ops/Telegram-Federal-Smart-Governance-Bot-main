package com.tg.heyisheng.bot.core.callback;

import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

import java.util.Optional;

/**
 * 处理一类内联按钮回调。
 *
 * <p><b>存在理由</b>：本项目此前只处理 message / edited_message / channel_post 三类来源
 * （{@code UpdateDispatcher.relevantMessage}），<b>没有按钮点击这条入口</b>——
 * 而「入群验证点按钮」「审核裁决点按钮」都依赖它。本接口是模块四引入的新入口契约。
 *
 * <p>路由按 {@link #action()} 前缀匹配 {@code CallbackQuery.getData()} 中第一个 {@code ':'} 之前的部分，
 * 例如 {@code data = "verify:-100123:42"} → action 为 {@code "verify"}。
 */
public interface CallbackHandler {

    /** 本处理器负责的 action 前缀（不含冒号）。 */
    String action();

    /**
     * 处理回调。
     *
     * <p><b>必须返回一个 {@code AnswerCallbackQuery}</b>（或等价应答）——否则客户端按钮会一直转圈。
     * 实现应在内部处理自己的业务分支，返回"应答即可"的方法。
     *
     * @param query 回调
     * @return 需要执行的 Bot API 方法；返回空表示不处理
     */
    Optional<BotApiMethod<?>> handle(CallbackQuery query);
}
