package com.tg.heyisheng.bot.escrow;

import com.tg.heyisheng.bot.common.exception.TggException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 模块十二 · 担保交易服务：承载<b>订单状态机</b>（调研报告 §3 gap-ESC-02 + 规格 §13.2）。
 *
 * <pre>
 * open ─▶ OPEN ─confirm(seller)─▶ CONFIRMED ─lock(buyer)─▶ LOCKED ─deliver(seller)─▶ DELIVERED
 *        （待卖方确认）          （待买方托管）      （托管中）                        （待验收）
 *                                                       │                                │
 *                                                       │◀────── dispute(party) ─────────┘
 *                                                       ▼
 *                                                   DISPUTED ──┬─ release(buyer/裁决) ─▶ RELEASED
 *                                                  （争议中）   └─ refund(party/裁决)  ─▶ REFUNDED
 *
 * OPEN / CONFIRMED ─cancel(party)─▶ CANCELLED（仅资金未托管时）
 * </pre>
 *
 * <p><b>身份闸门（本波新增）</b>：每个迁移都要求<b>指定身份</b>的动作方——
 * 卖方才能确认与交付、买方才能托管与验收放款、当事方（买卖任一）才能争议/退款/取消。
 * 在此之前服务层不做身份判定：一旦接上命令入口，那等于「任何人都能替别人放款」。
 * 守卫与状态校验都在 {@code save} <b>之前</b>，非法调用不留半推进状态。
 *
 * <p><b>「完整做」的边界</b>：账本 / 状态机 / 裁决判定<b>全部是本地真实逻辑</b>，可真库端到端验证。
 * 链上那一半（真实 lock/refund 落链）属 gap-ESC-01，不在本波范围——本模块当前不依赖
 * {@code DepositGateway}，链上接入待后续波以 {@code @Primary} 接缝补齐（调研报告 §4.1 A3）。
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
     * 某用户参与的全部订单（作为买方或卖方），编号升序——{@code /escrow list} 的数据源。
     *
     * <p>两次单边查询后合并：自担保在 {@link #open} 已被拒绝，故同一订单不会在两边同时出现。
     */
    public List<EscrowOrder> ordersOf(long userId) {
        List<EscrowOrder> all = new ArrayList<>(orders.findByBuyerUserIdOrderByIdAsc(userId));
        all.addAll(orders.findBySellerUserIdOrderByIdAsc(userId));
        all.sort(Comparator.comparing(EscrowOrder::getId));
        return all;
    }

    /**
     * 创建担保订单：新建一笔 {@code OPEN} 记录（待卖方确认）。
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

    /** 卖方确认接单：{@code OPEN} → {@code CONFIRMED}。仅卖方。 */
    @Transactional
    public Optional<EscrowOrder> confirm(long orderId, long actorUserId) {
        return transition(orderId, order -> {
            requireSeller(order, actorUserId);
            order.markConfirmed(clock.instant());
        });
    }

    /** 买方托管资金：{@code OPEN} / {@code CONFIRMED} → {@code LOCKED}。仅买方。 */
    @Transactional
    public Optional<EscrowOrder> lock(long orderId, long actorUserId) {
        return transition(orderId, order -> {
            requireBuyer(order, actorUserId);
            order.markLocked(clock.instant());
        });
    }

    /** 卖方交付：{@code LOCKED} → {@code DELIVERED}（待买方验收）。仅卖方。 */
    @Transactional
    public Optional<EscrowOrder> deliver(long orderId, long actorUserId) {
        return transition(orderId, order -> {
            requireSeller(order, actorUserId);
            order.markDelivered(clock.instant());
        });
    }

    /** 买方验收放款：{@code LOCKED} / {@code DELIVERED} / {@code DISPUTED} → {@code RELEASED}。仅买方。 */
    @Transactional
    public Optional<EscrowOrder> release(long orderId, long actorUserId) {
        return transition(orderId, order -> {
            requireBuyer(order, actorUserId);
            order.markReleased(clock.instant());
        });
    }

    /** 发起争议：{@code LOCKED} / {@code DELIVERED} → {@code DISPUTED}（必带理由）。仅当事方。 */
    @Transactional
    public Optional<EscrowOrder> dispute(long orderId, long actorUserId, String reason) {
        requireReason(reason, "发起争议");
        return transition(orderId, order -> {
            requireParty(order, actorUserId);
            order.markDisputed(reason, clock.instant());
        });
    }

    /** 退款给买家：{@code LOCKED} / {@code DELIVERED} / {@code DISPUTED} → {@code REFUNDED}（必带理由）。仅当事方。 */
    @Transactional
    public Optional<EscrowOrder> refund(long orderId, long actorUserId, String reason) {
        requireReason(reason, "退款");
        return transition(orderId, order -> {
            requireParty(order, actorUserId);
            order.markRefunded(reason, clock.instant());
        });
    }

    /**
     * 协商取消：{@code OPEN} / {@code CONFIRMED} → {@code CANCELLED}（必带理由）。仅当事方。
     *
     * <p>资金已托管时实体层会拒绝（见 {@code EscrowOrder#markCancelled}）——要退出须走退款。
     */
    @Transactional
    public Optional<EscrowOrder> cancel(long orderId, long actorUserId, String reason) {
        requireReason(reason, "取消");
        return transition(orderId, order -> {
            requireParty(order, actorUserId);
            order.markCancelled(reason, clock.instant());
        });
    }

    /**
     * 争议期天数（{@code tgg.escrow.dispute-window-days}）。
     *
     * <p>供命令层拼提示文案（"有问题请在 N 天内发起争议"）——文案里写死数字会与配置漂移。
     */
    public int disputeWindowDays() {
        return properties.getDisputeWindowDays();
    }

    /**
     * 争议期截止时刻（自订单创建起算，天）——由 {@code tgg.escrow.dispute-window-days} 控制。
     * 查询侧与裁决队列据此判断争议窗口。
     */
    public Instant disputeDeadline(EscrowOrder order) {
        return order.getCreatedAt().plus(Duration.ofDays(properties.getDisputeWindowDays()));
    }

    /**
     * 订单是否已超时未托管（自创建起算，小时）——由 {@code tgg.escrow.order-timeout-hours} 控制。
     * 仅 {@code OPEN} / {@code CONFIRMED} 订单适用；已托管 / 终态订单恒为 false。
     */
    public boolean isLockExpired(EscrowOrder order) {
        String state = order.getState();
        boolean beforeCustody = EscrowOrder.State.OPEN.name().equals(state)
                || EscrowOrder.State.CONFIRMED.name().equals(state);
        return beforeCustody && clock.instant().isAfter(order.getCreatedAt().plus(Duration.ofHours(
                properties.getOrderTimeoutHours())));
    }

    /**
     * 统一迁移入口：守卫<b>先于</b>落库。
     *
     * <p>身份校验与状态校验都在 {@code mutation} 内完成，它抛出的 {@link TggException}
     * 传播出去时 {@code save} 尚未被调用——非法调用不会写库（「不留下半推进状态」的落点）。
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

    // ───────────────────────── 身份守卫（fail-closed） ─────────────────────────

    private static void requireSeller(EscrowOrder order, long actorUserId) {
        if (order.getSellerUserId() != actorUserId) {
            throw new TggException("该操作仅限卖方（订单 #" + order.getId() + "，操作者不是卖方）");
        }
    }

    private static void requireBuyer(EscrowOrder order, long actorUserId) {
        if (order.getBuyerUserId() != actorUserId) {
            throw new TggException("该操作仅限买方（订单 #" + order.getId() + "，操作者不是买方）");
        }
    }

    private static void requireParty(EscrowOrder order, long actorUserId) {
        if (order.getBuyerUserId() != actorUserId && order.getSellerUserId() != actorUserId) {
            throw new TggException("该操作仅限交易当事方（订单 #" + order.getId() + "，操作者不是买卖任一方）");
        }
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
