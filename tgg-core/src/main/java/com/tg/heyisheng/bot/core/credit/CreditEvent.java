package com.tg.heyisheng.bot.core.credit;

import com.tg.heyisheng.bot.core.moderation.RiskLevel;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 信用事件——模块七的输入端。
 *
 * <p><b>刻意不含消息正文</b>（沿用 {@link com.tg.heyisheng.bot.core.moderation.ModerationVerdict}
 * 的纪律）：本对象会被发布到信用服务、进而进日志与账本，因此只能携带"发生了什么"的结论，
 * 不能携带被判定内容，否则正文会经信用事件这条侧路泄露。
 *
 * <p><b>{@code hardLine} 与 {@code severity} 分离</b>：与 {@code ModerationVerdict} 同构——
 * 硬红线（诈骗/儿童色情）不走等级比较，其处置是"立即触底"，故单独标记，不能只靠 {@link RiskLevel}。
 *
 * @param eventId     事件唯一 id（UUID），用于幂等与去重
 * @param subjectType 被评分主体类型
 * @param subjectId   被评分主体 id（个人=userId；群组=chatId；商家=商户 id）
 * @param eventType   事件类型
 * @param severity    风险等级（多条命中取最高，由生产侧决定）
 * @param hardLine    是否硬红线
 * @param source      来源模块标识（如 "moderation" / "wordfilter" / "admission"）
 * @param occurredAt  发生时间
 * @param federationReport <b>显式要求上报联邦</b>（不靠分数阈值触发）。用于「按次数递进」的场景：
 *                   如模块九 §10.5 的「敏感话题三次 → 联邦标记」，其 100−5−15−30 = 50 分
 *                   <b>永远到不了</b> 触发线（≤0），故必须由生产侧显式声明，而非指望分数副作用。
 */
public record CreditEvent(
        String eventId,
        CreditSubjectType subjectType,
        long subjectId,
        CreditEventType eventType,
        RiskLevel severity,
        boolean hardLine,
        String source,
        Instant occurredAt,
        boolean federationReport
) {

    public CreditEvent {
        Objects.requireNonNull(eventId, "eventId 不可为空");
        Objects.requireNonNull(subjectType, "subjectType 不可为空");
        Objects.requireNonNull(eventType, "eventType 不可为空");
        Objects.requireNonNull(severity, "severity 不可为空");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    /**
     * 便捷工厂：自动生成 {@code eventId}（UUID）与时间戳。
     *
     * <p>生产侧优先用它，避免各处自行拼装导致 id 缺失或时间口径不一。
     */
    public static CreditEvent of(CreditSubjectType subjectType, long subjectId,
                                 CreditEventType eventType, RiskLevel severity,
                                 boolean hardLine, String source) {
        return of(subjectType, subjectId, eventType, severity, hardLine, source, false);
    }

    /** 便捷工厂（带显式联邦上报标记）。 */
    public static CreditEvent of(CreditSubjectType subjectType, long subjectId,
                                 CreditEventType eventType, RiskLevel severity,
                                 boolean hardLine, String source, boolean federationReport) {
        return new CreditEvent(UUID.randomUUID().toString(), subjectType, subjectId,
                eventType, severity, hardLine, source, Instant.now(), federationReport);
    }
}
