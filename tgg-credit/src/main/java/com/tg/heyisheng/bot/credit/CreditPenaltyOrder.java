package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditSubjectType;

import java.time.Instant;
import java.util.Objects;

/**
 * 信用处罚令（模块七）。
 *
 * <p>模块七负责<b>产出</b>处罚令（判定 + 签名）；真实执行（警告/禁言/移除）与跨节点广播
 * 由消费方负责。签名用于让下游（联邦节点）能验证"该令确由本节点签发、未被篡改"。
 *
 * @param orderId     令唯一 id（UUID）
 * @param subjectType 被处罚主体类型
 * @param subjectId   被处罚主体 id
 * @param penaltyType 处罚档位
 * @param issuedAt    签发时间
 * @param signature   HMAC-SHA256 签名（十六进制）；未签名时为 null
 */
public record CreditPenaltyOrder(
        String orderId,
        CreditSubjectType subjectType,
        long subjectId,
        PenaltyType penaltyType,
        Instant issuedAt,
        String signature
) {

    public CreditPenaltyOrder {
        Objects.requireNonNull(orderId, "orderId 不可为空");
        Objects.requireNonNull(subjectType, "subjectType 不可为空");
        Objects.requireNonNull(penaltyType, "penaltyType 不可为空");
        issuedAt = issuedAt == null ? Instant.now() : issuedAt;
    }

    /**
     * 参与签名的规范串。
     *
     * <p><b>字段顺序固定、用 {@code |} 分隔</b>：签名与验签两侧必须逐字一致，
     * 顺序或分隔符漂移会让验签静默失败（{@code LESSONS.md} 坑 3"同一值两种语义"的同类风险）。
     * 时间用 epoch 毫秒，避免时区/格式歧义。
     */
    public String canonicalString() {
        return orderId + "|" + subjectType.name() + "|" + subjectId + "|"
                + penaltyType.name() + "|" + issuedAt.toEpochMilli();
    }

    /** 附加签名（返回新实例，record 不可变）。 */
    public CreditPenaltyOrder withSignature(String signature) {
        return new CreditPenaltyOrder(orderId, subjectType, subjectId, penaltyType, issuedAt, signature);
    }
}
