package com.tg.heyisheng.bot.escrow;

import com.tg.heyisheng.bot.common.exception.TggException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;

/**
 * 担保交易订单账本（表 {@code escrow_orders}，模块十二 · 调研报告 §3 gap-ESC-02）。
 *
 * <p>表结构由 Flyway <b>V24</b> 管理，JPA 侧 {@code ddl-auto: validate}。
 *
 * <p><b>状态机</b>（调研报告 §2 接缝 + gap-ESC-02）：
 * <pre>
 * OPEN ──markLocked──▶ LOCKED ──┬─ markReleased ─▶ RELEASED（终态）
 *  (待锁仓)        (已锁仓)      ├─ markRefunded ─▶ REFUNDED（终态）
 *                               └─ markDisputed ─▶ DISPUTED ──┬─ markReleased ─▶ RELEASED（裁决归卖家）
 *                                (争议中)                      └─ markRefunded ─▶ REFUNDED（裁决归买家）
 * </pre>
 *
 * <p><b>金额是 {@link BigDecimal} 而不是 double</b>：{@code DECIMAL(24,8)} 是精确十进制，
 * 用二进制浮点表示会在比对与求差时引入不可解释的尾差——担保交易里这是资金问题，不是显示问题。
 *
 * <p><b>fail-closed 两处落点</b>：
 * <ol>
 *   <li><b>非法迁移一律抛异常</b>：静默接受越级迁移（如 OPEN 直接 RELEASED）等于凭空放款；</li>
 *   <li><b>未知状态值亦抛异常</b>：从库中读到不在枚举内的状态（数据被篡改 / 版本漂移）时，
 *       拒绝继续推进，而不是 {@code IllegalArgumentException} 逃逸或按最宽松状态放行。</li>
 * </ol>
 *
 * <p>各 {@code markX} 只做「守卫 + 迁移」，不含任何编排；编排与业务前置校验在
 * {@link EscrowService}，且守卫<b>先于</b>落库——与模块六「先守卫再触碰不可回滚的外部动作」同纪律。
 */
@Entity
@Table(name = "escrow_orders")
public class EscrowOrder {

    /** 担保订单状态。取值即流程节点，与 {@code escrow_orders.state} 列的字符串一一对应。 */
    public enum State {
        /** 已创建（待卖方确认）。 */
        OPEN,
        /** 卖方已确认（待买方托管资金）。 */
        CONFIRMED,
        /** 已锁仓（资金托管中）。 */
        LOCKED,
        /** 卖方已交付（待买方验收；验收后放款，超时按约定处置）。 */
        DELIVERED,
        /** 争议中（暂停自动结算，待裁决）。 */
        DISPUTED,
        /** 已放款给卖家（终态）。 */
        RELEASED,
        /** 已退款给买家（终态）。 */
        REFUNDED,
        /**
         * 已取消（协商取消，或未托管前超时关闭）。
         *
         * <p><b>只允许在资金未托管时进入</b>（{@code OPEN} / {@code CONFIRMED}）——
         * 托管后要"就地取消"就必须动已锁定的资金，那只能经
         * {@link #markRefunded} 走退款路径，不可用取消绕过资金流程。
         */
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "buyer_user_id", nullable = false)
    private long buyerUserId;

    @Column(name = "seller_user_id", nullable = false)
    private long sellerUserId;

    @Column(name = "amount", nullable = false, precision = 24, scale = 8)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EscrowOrder() {
    }

    /** 新建一笔待锁仓的担保订单：状态恒从 {@link State#OPEN} 起步。 */
    public EscrowOrder(long buyerUserId, long sellerUserId, BigDecimal amount,
                       String currency, Instant createdAt) {
        this.buyerUserId = buyerUserId;
        this.sellerUserId = sellerUserId;
        this.amount = amount;
        this.currency = currency;
        this.state = State.OPEN.name();
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /** 卖方确认接单：{@code OPEN} → {@code CONFIRMED}。 */
    public void markConfirmed(Instant now) {
        requireState(State.OPEN);
        this.state = State.CONFIRMED.name();
        this.updatedAt = now;
    }

    /**
     * 锁仓成功：{@code OPEN} 或 {@code CONFIRMED} → {@code LOCKED}。
     *
     * <p>允许从 {@code OPEN} 直接锁仓：W2 骨架的既有流程没有"卖方确认"这一独立节点；
     * {@code CONFIRMED} 是为规格 §13.2 的三步创建流程（买方创建 → 卖方确认 → 资金锁定）预留的中间态。
     */
    public void markLocked(Instant now) {
        requireState(State.OPEN, State.CONFIRMED);
        this.state = State.LOCKED.name();
        this.updatedAt = now;
    }

    /** 卖方交付：{@code LOCKED} → {@code DELIVERED}（待买方验收）。 */
    public void markDelivered(Instant now) {
        requireState(State.LOCKED);
        this.state = State.DELIVERED.name();
        this.updatedAt = now;
    }

    /** 发起争议：{@code LOCKED} 或 {@code DELIVERED} → {@code DISPUTED}（交付后也能争议）。 */
    public void markDisputed(String reason, Instant now) {
        requireState(State.LOCKED, State.DELIVERED);
        this.reason = reason;
        this.state = State.DISPUTED.name();
        this.updatedAt = now;
    }

    /** 放款给卖家：{@code LOCKED} / {@code DELIVERED} / {@code DISPUTED} → {@code RELEASED}。 */
    public void markReleased(Instant now) {
        requireState(State.LOCKED, State.DELIVERED, State.DISPUTED);
        this.state = State.RELEASED.name();
        this.updatedAt = now;
    }

    /** 退款给买家：{@code LOCKED} / {@code DELIVERED} / {@code DISPUTED} → {@code REFUNDED}。 */
    public void markRefunded(String reason, Instant now) {
        requireState(State.LOCKED, State.DELIVERED, State.DISPUTED);
        this.reason = reason;
        this.state = State.REFUNDED.name();
        this.updatedAt = now;
    }

    /**
     * 取消订单：{@code OPEN} 或 {@code CONFIRMED} → {@code CANCELLED}。
     *
     * <p><b>刻意不允许从 {@code LOCKED}/{@code DELIVERED} 取消</b>：那时资金已托管，
     * 要退出必须走退款（{@link #markRefunded}）——用这条守卫保证"资金已动"的订单
     * 永远无法绕过资金流程被就地取消。
     */
    public void markCancelled(String reason, Instant now) {
        requireState(State.OPEN, State.CONFIRMED);
        this.reason = reason;
        this.state = State.CANCELLED.name();
        this.updatedAt = now;
    }

    /** 解析当前状态——未知值 fail-closed（拒绝推进，而非抛裸 {@code IllegalArgumentException}）。 */
    private State currentState() {
        if (this.state == null) {
            throw new TggException("担保订单状态缺失（fail-closed）" + idSuffix());
        }
        try {
            return State.valueOf(this.state);
        } catch (IllegalArgumentException ex) {
            throw new TggException("担保订单状态非法（fail-closed）：" + this.state + idSuffix());
        }
    }

    private void requireState(State... allowed) {
        State current = currentState();
        for (State candidate : allowed) {
            if (current == candidate) {
                return;
            }
        }
        throw new TggException("担保订单状态迁移非法：当前 " + current + "，本操作只允许 "
                + Arrays.toString(allowed) + idSuffix());
    }

    private String idSuffix() {
        return id == null ? "" : "（订单 #" + id + "）";
    }

    public Long getId() {
        return id;
    }

    public long getBuyerUserId() {
        return buyerUserId;
    }

    public long getSellerUserId() {
        return sellerUserId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getState() {
        return state;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
