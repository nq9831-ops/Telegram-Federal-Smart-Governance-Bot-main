package com.tg.heyisheng.bot.listing.merchant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 保证金网关的<b>默认实现</b>：只记日志、不产生任何链上动作（设计文档 §3.1 的接入位）。
 *
 * <p>默认装配它意味着：保证金账本、状态机、退还三分支、审计流水<b>全部真实工作</b>，
 * 唯独「链上那一半」是本地的。这与 {@code LoggingSubmitterNotifier}（通知只记日志）、
 * 模块九 L2/L4（本地模型层默认不装配）是同一套取舍。
 *
 * <p><b>每次都打 WARN</b>：不这么做，运维很容易以为「保证金已上链锁定」而事实上链上什么都没有
 * ——静默的假成功比显式失败危险得多。真实实现由部署方以 {@code @Primary} 覆盖本 bean 即可。
 */
public class NoopDepositGateway implements DepositGateway {

    private static final Logger log = LoggerFactory.getLogger(NoopDepositGateway.class);

    /** 引用里带上单调序号，便于在流水/日志中区分不同次动作（本机不落链，序号即唯一性来源）。 */
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public String lock(long merchantId, BigDecimal amount, String currency) {
        String ref = "noop-lock-" + merchantId + "-" + sequence.incrementAndGet();
        log.warn("保证金【未上链】锁定：merchantId={} amount={} {} ref={} —— 当前为 NoopDepositGateway，"
                + "链上无任何动作；真实实现由部署方替换本 bean。", merchantId, amount, currency, ref);
        return ref;
    }

    @Override
    public void freeze(long merchantId, String gatewayRef) {
        log.warn("保证金【未上链】冻结：merchantId={} ref={} —— NoopDepositGateway，链上无任何动作。",
                merchantId, gatewayRef);
    }

    @Override
    public void refund(long merchantId, String gatewayRef, BigDecimal amount) {
        log.warn("保证金【未上链】退还：merchantId={} amount={} ref={} —— NoopDepositGateway，链上无任何动作。",
                merchantId, amount, gatewayRef);
    }

    @Override
    public void deduct(long merchantId, String gatewayRef, BigDecimal amount, String reason) {
        log.warn("保证金【未上链】扣除：merchantId={} amount={} ref={} —— NoopDepositGateway，链上无任何动作。",
                merchantId, amount, gatewayRef);
    }
}
