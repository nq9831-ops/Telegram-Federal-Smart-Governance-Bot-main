package com.tg.heyisheng.bot.listing.verification;

import java.time.Duration;

/**
 * 可注入的「等待」接缝——只为让重试退避在测试里不必真等 5 分钟。
 *
 * <p>生产装配 {@link #threadSleep()}；测试注入一个<b>只记录调用</b>的实现，
 * 既能断言「退避真的发生了、间隔就是配置值」，又让用例瞬间跑完。
 *
 * <p>为什么是接口而非「把间隔配成 0」：配置成 0 的测试等于<b>没有验证退避</b>——
 * 它无法区分「按配置退了避」与「压根没重试」。注入替身才能同时断言<b>次数与时长</b>。
 */
@FunctionalInterface
public interface Sleeper {

    /** 等待指定时长；实现应响应中断（不静默吞掉）。 */
    void sleep(Duration duration);

    /** 默认实现：真实线程睡眠，中断时保留中断标志并抛出（交给上层记录并终止本轮）。 */
    static Sleeper threadSleep() {
        return duration -> {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("验证重试等待被中断", ex);
            }
        };
    }
}
