package com.tg.heyisheng.bot.listing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 失效申诉（表 {@code listing_appeals}，模块五）。
 *
 * <p>字段照设计文档 §4 的 {@code ListingAppeal}：{@code id / listingId / userId / text / status / createdAt}。
 * 表结构由 Flyway <b>V7</b> 管理（V6 已在真实库应用，改它会触发 checksum 不匹配——见 LESSONS 坑 8），
 * JPA 侧 {@code ddl-auto: validate}。
 *
 * <p><b>为什么必须有申诉入口</b>：判失效是<b>软删</b>——行仍留在 {@code listing_groups} 里可审计，
 * 但对外不可见。没有申诉，被误判的群主除了投诉无路可走，而「误判」在本模块是真实可能的
 * （探针的保守三态只挡掉了「探测失败」，挡不掉「Telegram 侧数据陈旧」）。
 *
 * <p><b>{@link #text} 是用户主动提交的管理输入</b>（同 {@code federation_appeals.appeal_text} 口径）：
 * 它不是被监听的对话正文，因此落库与「消息原文零存储」不冲突；但本类与调用方<b>都不得把它写进日志</b>
 * （日志脱敏是硬约束）。列名用 {@code appeal_text}：{@code text} 与 MySQL 类型关键字同形，
 * 避开它是零成本的稳妥选择（字段名仍照设计文档叫 {@code text}）。
 */
@Entity
@Table(name = "listing_appeals")
public class ListingAppeal {

    /** 申诉状态：{@code PENDING} 待审（新建默认）；{@code APPROVED} / {@code REJECTED} 为裁定结果。 */
    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "appeal_text")
    private String text;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ListingAppeal() {
    }

    /** 新建一条待审申诉（status 恒为 {@link Status#PENDING}——「已裁定」只能由裁定流程写入）。 */
    public ListingAppeal(Long listingId, Long userId, String text, Instant createdAt) {
        this.listingId = listingId;
        this.userId = userId;
        this.text = text;
        this.status = Status.PENDING.name();
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getListingId() {
        return listingId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getText() {
        return text;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
