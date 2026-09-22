package com.tg.heyisheng.bot.core.failover;

import com.tg.heyisheng.bot.core.dispatch.UpdateDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 降级（长轮询）模式下的「分发 update → 发送回复 → 成败观测」编排。
 *
 * <p><b>为什么单独成类</b>：这段逻辑原先内联在 {@code FailoverConfiguration} 的
 * {@code LongPollingUpdateConsumer} lambda 里——lambda 无法单测，于是
 * 「回复到底发出去没有」这件事没有任何测试与观测的落点。更糟的是它写成
 * {@code dispatch(update).ifPresent(executor::execute)}：{@link TelegramApiMethodExecutor#execute}
 * 返回的 {@code boolean} 被 {@code ifPresent} <b>整个丢弃</b>，降级期「消息收得到、回复发不出去」
 * 只在 executor 内部留一条 warn，聚合层面完全看不见。把它提出来，发送成败才有归属。
 */
public class PollingReplySender {

    private static final Logger log = LoggerFactory.getLogger(PollingReplySender.class);

    private final UpdateDispatcher updateDispatcher;
    private final TelegramApiMethodExecutor executor;

    /**
     * 累计发送失败次数。
     *
     * <p><b>为什么是累计计数而非时间窗</b>：它服务的是「降级期有没有在丢回复、丢了多少」这个判断，
     * 而不是速率。缺了它只能翻 executor 的逐条日志，得不到量级。
     */
    private final AtomicLong sendFailures = new AtomicLong();

    public PollingReplySender(UpdateDispatcher updateDispatcher, TelegramApiMethodExecutor executor) {
        this.updateDispatcher = updateDispatcher;
        this.executor = executor;
    }

    /**
     * 分发一条 update 并把其回复发往 Telegram。
     *
     * <p><b>不退回 {@code Consumer}</b>：发送器返回 {@code boolean}，这里必须显式消费这个结果——
     * 把它适配成 {@code Consumer} 就等于再次丢掉成败，正是本类要根除的失败形态。
     *
     * @return {@code true} = 本条 update 产出了回复但发送失败；{@code false} = 无回复或发送成功
     */
    public boolean handleAndObserve(Update update) {
        Optional<BotApiMethod<?>> reply;
        try {
            reply = updateDispatcher.dispatch(update);
        } catch (Exception ex) {
            // 分发本身出错不该中断整批 update 的处理——照旧只记录，且不计入「发送失败」。
            log.warn("长轮询模式下分发 update 失败：{}", ex.getClass().getSimpleName(), ex);
            return false;
        }
        if (reply.isEmpty()) {
            return false;
        }
        BotApiMethod<?> method = reply.get();
        if (executor.execute(method)) {
            return false;
        }
        // 发送失败：ERROR + 累计计数，让「降级期回复丢失」在聚合层可见。
        // 不做自动重发：长轮询回复没有幂等键，重发可能造成重复消息；有限重发留作后续按需引入。
        long failures = sendFailures.incrementAndGet();
        log.error("降级模式下回复发送失败（累计 {} 次）：method={}", failures, method.getMethod());
        return true;
    }

    /** 累计发送失败次数——供观测/告警读取。 */
    public long sendFailureCount() {
        return sendFailures.get();
    }
}
