package com.tg.heyisheng.bot.escrow;

import com.tg.heyisheng.bot.common.exception.TggException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 模块十二 · 担保交易服务：承载<b>订单状态机</b>（调研报告 §3 gap-ESC-02 骨架）。
 *
 * <pre>
 * open ──▶ OPEN ──lock──▶ LOCKED ──┬─ release              ─▶ RELEASED
 *        （已创建）     （已锁仓）  ├─ refund(reason)       ─▶ REFUNDED
 *                                 └─ dispute(reason) ─▶ DISPUTED ──┬─ release        ─▶ RELEASED
 *                                        （争议中）               └─ refund(reason) ─▶ REFUNDED
 * </pre>
 *
 * <p><b>fail-closed 的三处落点</b>：
 * <ol>
 *   <li><b>守卫先于落库</b>：{@link #transition} 先让实体方法做状态校验，非法迁移在
 *       {@code save} <b>之前</b>抛 {@link TggException}，绝不留下「状态被推进了一半」；</li>
 *   <li><b>争议 / 退款必带理由</b>：理由为空直接拒绝，且拒绝发生在实体迁移之前；</li>
 *   <li><b>买卖双方不得同人</b>：担保交易需两个主体，自担保无意义（拒绝，而非静默放行）。</li>
 * </ol>
 *
 * <p><b>「完整做」的边界</b>：账本 / 状态机 / 争议裁决判定<b>全部是本地真实逻辑</b>，
 * 可在真库上端到端验证。链上那一半（真实 lock/refund 落链）属 gap-ESC-01，<b>不在本波范围</b>
 * ——本模块当前不依赖 {@code DepositGateway}，链上接入待后续波以 {@code @Primary} 接缝补齐
 * （调研报告 §4.1 A3：接口签名不动，避免链上不可验证代码污染主线）。
 */
public class EscrowService {

    private static final Logger log = LoggerFactory.getLogger(EscrowService.class);

    /** 缺省币种（V24 的 {@code currency} 列默认值）。 */
    static final String DEFAULT_CURRENCY = "USDT";

    private final EscrowRepository orders;
    private final EscrowProperties properties;
    private final Clock clock;

    public EscrowService(EscrowRepository orders, EscrowProperties properties, Clock clock) {
        this.orders = orders;
        this.properties = properties;
        this.clock = clock;
    }

    /** 取某订单（查询侧）。 */
    public Optional<EscrowOrder> find(long orderId) {
        return orders.findById(orderId);
    }

    /**
     * 创建担保订单：新建一笔 {@code OPEN} 记录（待锁仓）。
     *
     * @throws TggException 金额非正 / 买卖双方同人
     */
    @Transactional
    public EscrowOrder open(long buyerUserId, long sellerUserId, BigDecimal amount, String currency) {
        if (buyerUserId == sellerUserId) {
            throw new TggException("担保交易的买家与卖家不得为同一人（userId " + buyerUserId + "）");
        }
        requirePositive(amount);
        Instant now = clock.instant();
        EscrowOrder order = orders.save(new EscrowOrder(buyerUserId, sellerUserId, amount,
                (currency == null || currency.isBlank()) ? DEFAULT_CURRENCY : currency, now));
        log.info("担保订单已创建（OPEN）：买家 #{} 卖家 #{} 金额 {}，争议期 {} 天。",
                buyerUserId, sellerUserId, amount, properties.getDisputeWindowDays());
        return order;
    }

    /** 锁仓：{@code OPEN} → {@code LOCKED}。 */
    @Transactional
    public Optional<EscrowOrder> lock(long orderId) {
        return transition(orderId, order -> order.markLocked(clock.instant()));
    }

    /** 发起争议：{@code LOCKED} → {@code DISPUTED}（必带理由）。 */
    @Transactional
    public Optional<EscrowOrder> dispute(long orderId, String reason) {
        requireReason(reason, "发起争议");
        return transition(orderId, order -> order.markDisputed(reason, clock.instant()));
    }

    /** 放款给卖家：{@code LOCKED} / {@code DISPUTED} → {@code RELEASED}。 */
    @Transactional
    public Optional<EscrowOrder> release(long orderId) {
        return transition(orderId, order -> order.markReleased(clock.instant()));
    }

    /** 退款给买家：{@code LOCKED} / {@code DISPUTED} → {@code REFUNDED}（必带理由）。 */
    @Transactional
    public Optional<EscrowOrder> refund(long orderId, String reason) {
        requireReason(reason, "退款");
        return transition(orderId, order -> order.markRefunded(reason, clock.instant()));
    }

    /**
     * 争议期截止时刻（自订单创建起算，天）——由 {@code tgg.escrow.dispute-window-days} 控制。
     * 查询侧与裁决队列据此判断争议窗口。
     */
    public Instant disputeDeadline(EscrowOrder order) {
        return order.getCreatedAt().plus(Duration.ofDays(properties.getDisputeWindowDays()));
    }

    /**
     * 订单是否已超时未锁仓（自创建起算，小时）——由 {@code tgg.escrow.order-timeout-hours} 控制。
     * 仅 {@code OPEN} 订单适用；已锁仓 / 终态订单恒为 false。
     */
    public boolean isLockExpired(EscrowOrder order) {
        return EscrowOrder.State.OPEN.name().equals(order.getState())
                && clock.instant().isAfter(order.getCreatedAt().plus(Duration.ofHours(
                        properties.getOrderTimeoutHours())));
    }

    /**
     * 统一迁移入口：守卫<b>先于</b>落库。
     *
     * <p>状态校验在实体 {@code markX} 内（fail-closed），它抛出的 {@link TggException} 传播出去时
     * {@code save} 尚未被调用——非法迁移不会写库，这正是「不留下半推进状态」的落点。
     *
     * @return 空 = 订单不存在（无副作用）
     */
    private Optional<EscrowOrder> transition(long orderId, Consumer<EscrowOrder> mutation) {
        Optional<EscrowOrder> found = orders.findById(orderId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        EscrowOrder order = found.get();
        mutation.accept(order);
        return Optional.of(orders.save(order));
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new TggException("担保金额必须为正数（实为 " + amount + "）");
        }
    }

    private static void requireReason(String reason, String action) {
        if (reason == null || reason.isBlank()) {
            throw new TggException("担保交易「" + action + "」必须提供理由");
        }
    }
}
