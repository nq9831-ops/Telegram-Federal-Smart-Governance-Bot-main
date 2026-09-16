package com.tg.heyisheng.bot.common.model;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中间件链与命令分发共用的更新上下文。
 *
 * <p><b>刻意不持有消息正文</b>——这是「消息原文零存储」的前置结构约束。
 * 若在此处携带正文，后续整条链路（中间件、分发、日志）都会被动传递它；
 * 审核所需的内容由处理管道在内存中即时消费后丢弃。
 *
 * <p>本类只承载路由元数据，外加一个供中间件 enrich 用的<b>属性袋</b>。
 * 属性袋以<b>类型为键</b>：中间件把查到的数据放进来，handler 从<b>同一个实例</b>取出——
 * 这正是「上下文单实例贯通」的用途（若分发器自行重建上下文，挂载的数据会丢失）。
 *
 * <p><b>唯一的受限例外——{@link #commandArgs()}</b>：命令的「操作数」（命令名之后的文本，
 * 如 {@code /addword 广告词} 里的 {@code 广告词}）必须送达 handler 才能工作。
 * 它与「消息正文」的区别是明确的：它是用户<b>主动提交的管理输入</b>，不是被监听的对话内容；
 * 处理管道仍会照常清除消息正文（{@code MessageScrubber}），args <b>不进日志、不进审核判定、
 * 不进复核队列</b>，只在命令 handler 内被消费。
 * 不要把被审核的消息正文塞进这里——那会绕过「消息原文零存储」。
 */
public final class UpdateContext {

    private final Integer updateId;
    private final Long userId;
    private final Long chatId;
    /** 触发本次更新的消息 id。删除消息等处置动作需要它。 */
    private final Integer messageId;
    private final String command;
    /** 命令操作数（命令名之后的文本）；仅命令消息可能非空。见类 javadoc 的受限例外说明。 */
    private final String commandArgs;

    /**
     * 中间件 enrich 用的属性袋。
     *
     * <p>用并发容器是防御性的：处理单条 update 本应单线程，但若将来有异步分发，
     * 这里不应成为隐患。
     */
    private final Map<Class<?>, Object> attributes = new ConcurrentHashMap<>();

    /** 兼容构造器：不含消息 id（不需要处置动作的场景）。 */
    public UpdateContext(Integer updateId, Long userId, Long chatId, String command) {
        this(updateId, userId, chatId, null, command, null);
    }

    public UpdateContext(Integer updateId, Long userId, Long chatId, Integer messageId, String command) {
        this(updateId, userId, chatId, messageId, command, null);
    }

    public UpdateContext(Integer updateId, Long userId, Long chatId, Integer messageId,
                         String command, String commandArgs) {
        this.updateId = updateId;
        this.userId = userId;
        this.chatId = chatId;
        this.messageId = messageId;
        this.command = command;
        this.commandArgs = commandArgs;
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

    public Optional<Integer> messageId() {
        return Optional.ofNullable(messageId);
    }

    public Optional<String> command() {
        return Optional.ofNullable(command);
    }

    public boolean hasCommand() {
        return command != null && !command.isEmpty();
    }

    /**
     * 命令操作数（命令名之后的文本）。仅命令消息可能非空。
     *
     * <p>见类 javadoc 的「受限例外」说明——它<b>不是</b>消息正文，
     * 不得把它当作被审核内容的载体。
     */
    public Optional<String> commandArgs() {
        return Optional.ofNullable(commandArgs);
    }

    /**
     * 挂载中间件产出的数据，按<b>运行时类型</b>索引。
     *
     * <p>注意：以 {@code value.getClass()} 为键，因此取出时必须用同一类型
     * （用父类型取子类型实例会取不到）。
     *
     * @param value 待挂载的数据；null 会被忽略
     */
    public <T> void attach(T value) {
        if (value != null) {
            attributes.put(value.getClass(), value);
        }
    }

    /** 取出先前挂载的数据。 */
    public <T> Optional<T> find(Class<T> type) {
        return Optional.ofNullable(type.cast(attributes.get(type)));
    }

    /** 是否已挂载某类数据（便于测试与诊断）。 */
    public boolean has(Class<?> type) {
        return attributes.containsKey(type);
    }
}
