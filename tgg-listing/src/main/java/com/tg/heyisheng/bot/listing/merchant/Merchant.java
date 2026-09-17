package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.common.exception.TggException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;

/**
 * 商家（表 {@code merchants}，模块六）。
 *
 * <p>字段与设计文档 §5 的 {@code merchants} 表严格对应。表结构由 Flyway <b>V6</b> 管理
 * （V6 已建本表，本波次<b>不需要</b>新迁移），JPA 侧 {@code ddl-auto: validate}。
 *
 * <p><b>入驻状态机</b>（设计文档 §2「数据流（模块六）」）：
 * <pre>
 * SUBMITTED ──beginReview──▶ UNDER_REVIEW ──decide──▶ APPROVED / REJECTED / NEED_MORE
 *                                                              │                │
 *                                                  APPROVED ────┘                │
 *                                                     │                          │
 *                                        markDepositPending                 NEED_MORE
 *                                                     ▼                          │
 *                                              DEPOSIT_PENDING                   │
 *                                                     │                          │
 *                                                 markActive                （重新 beginReview）
 *                                                     ▼
 *                                                  ACTIVE
 * </pre>
 *
 * <p><b>非法迁移一律抛异常而不是静默改状态</b>：商家的状态直接决定它能否营业、能否被写入信用分账本，
 * 是「状态说了算」的领域对象。静默接受一个越级迁移（如 REJECTED → ACTIVE）会产出一个
 * 从未被审核通过的商家，且事后从数据上看不出异常——这是 fail-closed 与 fail-silent 的区别。
 *
 * <p><b>「拒绝/需补充」不是终局之外的终局</b>：{@code NEED_MORE} 之后允许重新送审
 * （{@link #beginReview}）；{@code REJECTED} 则不在本状态机内复活（重新申请须新建条目）。
 *
 * <p><b>凭证不落原文</b>：本实体只承载最小字段（名称/类别/简介/联系方式），
 * 无任何资质凭证原文列——凭证存储待 OCR/加密接入位落地时另建表（设计文档 §5 尾注）。
 */
@Entity
@Table(name = "merchants")
public class Merchant {

    /** 入驻状态。取值即 V5.0 的流程节点，与 {@code merchants.status} 列的字符串一一对应。 */
    public enum Status {
        /** 已提交，待复核。 */
        SUBMITTED,
        /** 复核中。 */
        UNDER_REVIEW,
        /** 资质通过（尚未缴纳保证金）。 */
        APPROVED,
        /** 需补充材料，可重新送审。 */
        NEED_MORE,
        /** 资质驳回。 */
        REJECTED,
        /** 待缴纳保证金（保证金流程由 Wave 5 驱动）。 */
        DEPOSIT_PENDING,
        /** 入驻成功（营业中）。 */
        ACTIVE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private long ownerUserId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "intro")
    private String intro;

    @Column(name = "contact", length = 255)
    private String contact;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "tier", length = 24)
    private String tier;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Merchant() {
    }

    /** 新建一条入驻申请：状态恒从 {@link Status#SUBMITTED} 起步。 */
    public Merchant(long ownerUserId, String name, String category, String intro, String contact,
                    Instant createdAt) {
        this.ownerUserId = ownerUserId;
        this.name = name;
        this.category = category;
        this.intro = intro;
        this.contact = contact;
        this.status = Status.SUBMITTED.name();
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /** 开始复核：{@code SUBMITTED} / {@code NEED_MORE} → {@code UNDER_REVIEW}。 */
    public void beginReview(Instant now) {
        requireStatus(Status.SUBMITTED, Status.NEED_MORE);
        this.status = Status.UNDER_REVIEW.name();
        this.updatedAt = now;
    }

    /**
     * 写入复核结论：{@code UNDER_REVIEW} → {@code APPROVED} / {@code REJECTED} / {@code NEED_MORE}。
     *
     * @param decision 复核结论；只接受上述三个目标态（传 SUBMITTED/UNDER_REVIEW/ACTIVE 等一律拒绝）
     */
    public void decide(Status decision, Instant now) {
        requireStatus(Status.UNDER_REVIEW);
        if (decision != Status.APPROVED && decision != Status.REJECTED && decision != Status.NEED_MORE) {
            throw new TggException("非法的复核结论：" + decision
                    + "（只接受 APPROVED / REJECTED / NEED_MORE）");
        }
        this.status = decision.name();
        this.updatedAt = now;
    }

    /** 资质通过后进入缴费阶段：{@code APPROVED} → {@code DEPOSIT_PENDING}。 */
    public void markDepositPending(Instant now) {
        requireStatus(Status.APPROVED);
        this.status = Status.DEPOSIT_PENDING.name();
        this.updatedAt = now;
    }

    /**
     * 保证金到位，入驻成功：{@code DEPOSIT_PENDING} → {@code ACTIVE}。
     *
     * <p><b>只做状态迁移</b>——信用分初始化不在这里发生：实体不应依赖记账服务，
     * 该动作由 {@code MerchantService#markActive} 在迁移成功后显式调用。
     */
    public void markActive(Instant now) {
        requireStatus(Status.DEPOSIT_PENDING);
        this.status = Status.ACTIVE.name();
        this.updatedAt = now;
    }

    /** 记录等级评定结果（等级规则由 Wave 6 的 {@code MerchantTierEvaluator} 产出）。 */
    public void assignTier(String tier, Instant now) {
        this.tier = tier;
        this.updatedAt = now;
    }

    /** 当前状态是否为 {@code ACTIVE}（营业中）。 */
    public boolean isActive() {
        return Status.ACTIVE.name().equals(status);
    }

    /** 是否为终态（{@code REJECTED}：不再流转，重新入驻须新建条目）。 */
    public boolean isRejected() {
        return Status.REJECTED.name().equals(status);
    }

    private void requireStatus(Status... allowed) {
        Status current = Status.valueOf(this.status);
        for (Status candidate : allowed) {
            if (current == candidate) {
                return;
            }
        }
        throw new TggException("商家状态迁移非法：当前 " + current + "，本操作只允许 "
                + Arrays.toString(allowed) + (id == null ? "" : "（商家 #" + id + "）"));
    }

    public Long getId() {
        return id;
    }

    public long getOwnerUserId() {
        return ownerUserId;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getIntro() {
        return intro;
    }

    public String getContact() {
        return contact;
    }

    public String getStatus() {
        return status;
    }

    public String getTier() {
        return tier;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
