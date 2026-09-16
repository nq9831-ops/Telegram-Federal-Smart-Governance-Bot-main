package com.tg.heyisheng.bot.common.model;

import java.util.Optional;

/**
 * 中间件链与命令分发共用的更新上下文。
 *
 * <p><b>刻意不持有消息正文</b>——这是切片 3「消息原文零存储」的前置结构约束。
 * 若在此处携带正文，后续整条链路（中间件、分发、日志）都会被动传递它；
 * 审核所需的内容由处理管道在内存中即时消费后丢弃。
 *
 * <p>本类只承载路由与识别所需的元数据。
 */
public final class UpdateContext {

    private final Integer updateId;
    private final Long userId;
    private final Long chatId;
    private final String command;

    public UpdateContext(Integer updateId, Long userId, Long chatId, String command) {
        this.updateId = updateId;
        this.userId = userId;
        this.chatId = chatId;
        this.command = command;
    }

    public Integer updateId() {
        return updateId;
    }

    public Long userId() {
        return userId;
    }

    public Long chatId() {
        return chatId;
    }

    public Optional<String> command() {
        return Optional.ofNullable(command);
    }

    public boolean hasCommand() {
        return command != null && !command.isEmpty();
    }
}
