package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.core.ratelimit.RateLimiter;

import java.util.List;
import java.util.Optional;

/**
 * 反刷屏：识别「同一用户在同一群重复发送相同内容」。
 *
 * <p><b>与 {@code RateLimitMiddleware} 的区别</b>（勿混为一谈）：
 * <ul>
 *   <li>限流中间件是<b>保护 Bot</b> 的粗粒度闸门——超限就静默丢弃，不删除、不通知；</li>
 *   <li>本检测器是<b>处置用户</b>：识别出刷屏后产出判定，走既有处置链（删除 + 中风险入复核队列）。</li>
 * </ul>
 *
 * <p><b>实现取舍</b>：直接复用 {@link RateLimiter} 做「群:用户:指纹」的滑动窗口计数——
 * 超出阈值即判定刷屏。这样不必另造窗口与驱逐逻辑，且自动享有 {@code InMemoryRateLimiter}
 * 的陈旧键清理（否则指纹 key 会随时间无界增长）。
 *
 * <p><b>隐私</b>：key 里只有内容<b>指纹</b>，不出现原文；本类也不持有正文。
 *
 * <p><b>阈值语义</b>：窗口内允许 N 次相同内容，第 N+1 次起判为刷屏。默认由装配层给出
 * （见 {@code TggCoreConfiguration}）——取值偏保守，避免把活跃群里连发「收到」的正常用户误伤。
 */
public class RepeatedMessageDetector {

    /** 命中时记入判定的规则 id（稳定值，供审计与统计）。 */
    static final String RULE_ID = "FLOOD_REPEAT";

    private final RateLimiter repeatLimiter;

    public RepeatedMessageDetector(RateLimiter repeatLimiter) {
        this.repeatLimiter = repeatLimiter;
    }

    /**
     * 检测是否构成重复刷屏。
     *
     * @param chatId  群组 ID
     * @param userId  发送者 ID（未知则不判——无法归因到"同一用户重复"）
     * @param content 消息正文（仅用于计算指纹，用完即弃）
     * @return 判定结果；未构成刷屏或无法判定时为空
     */
    public Optional<ModerationVerdict> inspect(Long chatId, Long userId, String content) {
        if (chatId == null || userId == null) {
            return Optional.empty();
        }
        String fingerprint = ContentFingerprint.of(content);
        if (fingerprint == null) {
            return Optional.empty();
        }

        String key = "f:" + chatId + ":" + userId + ":" + fingerprint;
        if (repeatLimiter.tryAcquire(key)) {
            return Optional.empty();
        }
        // 超出"窗口内同内容允许次数"——判定为重复刷屏（中风险，非硬红线，入复核队列）
        return Optional.of(new ModerationVerdict(RiskLevel.MEDIUM, false, List.of(RULE_ID)));
    }
}
