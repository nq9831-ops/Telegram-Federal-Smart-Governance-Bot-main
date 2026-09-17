package com.tg.heyisheng.bot.listing.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 单次链接验证记录（表 {@code listing_verification_records}，模块五）。
 *
 * <p>字段与设计文档 §5 的同名表严格对应；表结构由 Flyway V6 管理，
 * JPA 侧 {@code ddl-auto: validate}。
 *
 * <p><b>每种结果都要留痕</b>——包括 {@link VerificationResult#ERROR}。
 * 探测失败虽不推进状态机，但必须可审计：运维要能从记录区分
 * 「群真失效」与「探针/网络问题」，否则误下架无从复盘。
 *
 * <p>{@code detail} 只写状态描述，<b>不含任何群消息内容</b>（项目硬约束：消息原文零存储）。
 */
@Entity
@Table(name = "listing_verification_records")
public class VerificationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    @Column(name = "result", nullable = false, length = 16)
    private String result;

    @Column(name = "detail", length = 512)
    private String detail;

    protected VerificationRecord() {
    }

    public VerificationRecord(Long listingId, Instant verifiedAt, VerificationResult result, String detail) {
        this.listingId = listingId;
        this.verifiedAt = verifiedAt;
        this.result = result.name();
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Long getListingId() {
        return listingId;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public String getResult() {
        return result;
    }

    public String getDetail() {
        return detail;
    }
}
