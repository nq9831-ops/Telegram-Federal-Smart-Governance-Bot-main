package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.core.credit.CreditEvent;
import com.tg.heyisheng.bot.core.credit.CreditEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 信用事件适配器（模块七）：把 {@link CreditEventSink} 契约接到记账链路。
 *
 * <p>职责串联：记账 → 若触发处罚则产出<b>已签名</b>的处罚令 → 发布。
 *
 * <p><b>吞异常是契约</b>：本类是消息处理主链路上的一个旁挂环节（由 {@code UpdateDispatcher}
 * 在审核命中时调用）。任何失败都必须在此被吞掉——信用分是增强，不能因为记账出问题
 * 就让本该正常处理的消息处理失败。
 */
public class CreditEventSinkAdapter implements CreditEventSink {

    private static final Logger log = LoggerFactory.getLogger(CreditEventSinkAdapter.class);

    private final CreditService creditService;
    private final PenaltySigner signer;
    private final PenaltyOrderPublisher publisher;

    public CreditEventSinkAdapter(CreditService creditService,
                                  PenaltySigner signer,
                                  PenaltyOrderPublisher publisher) {
        this.creditService = creditService;
        this.signer = signer;
        this.publisher = publisher == null ? PenaltyOrderPublisher.noop() : publisher;
    }

    @Override
    public void publish(CreditEvent event) {
        try {
            CreditOutcome outcome = creditService.apply(event);
            if (outcome == null || !outcome.triggeredPenalty()) {
                return;
            }
            CreditPenaltyOrder unsigned = new CreditPenaltyOrder(
                    UUID.randomUUID().toString(),
                    outcome.subjectType(),
                    outcome.subjectId(),
                    outcome.penalty(),
                    null,
                    null);
            publisher.publish(signer.signAndAttach(unsigned));
        } catch (RuntimeException ex) {
            // 记账/签名/发布任一失败都不得中断消息处理主链路
            log.error("信用事件处理失败，已吞掉（不中断消息处理主链路）", ex);
        }
    }
}
