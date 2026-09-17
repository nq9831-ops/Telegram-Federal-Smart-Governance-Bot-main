package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.credit.CreditPenaltyOrder;
import com.tg.heyisheng.bot.credit.PenaltyVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 入站处罚令处理（模块八）：<b>验签 → 白名单 → 去重 → 落库 → 触发本地执行</b>。
 *
 * <p><b>来源如何确定</b>：不信任请求里的任何"我是谁"声明，而是用**已知节点的公钥逐个验签**——
 * 哪个节点的公钥能验通，来源就是它。这既实现了白名单（不在清单里的签名验不过），
 * 又杜绝了"伪造来源标识"（签名是用来源节点私钥做的，持他人公钥无法伪造）。
 *
 * <p><b>拒绝即不落库</b>：验签不过一律 {@link FederationIngestResult#REJECTED_BAD_SIGNATURE}，
 * 不写任何记录。
 */
public class FederationPenaltyService {

    private static final Logger log = LoggerFactory.getLogger(FederationPenaltyService.class);

    private final List<FederationNode> nodes;
    private final FederationPenaltyRepository repository;
    private final FederationBanHandler banHandler;

    public FederationPenaltyService(List<FederationNode> nodes,
                                    FederationPenaltyRepository repository,
                                    FederationBanHandler banHandler) {
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        this.repository = repository;
        this.banHandler = banHandler == null ? FederationBanHandler.noop() : banHandler;
    }

    /**
     * 处理一条入站处罚令。
     *
     * @param order 入站处罚令（含签名）
     * @return 处理结果；非 ACCEPTED/DUPLICATE 一律表示拒绝且未落库
     */
    @Transactional
    public FederationIngestResult ingest(CreditPenaltyOrder order) {
        if (order == null || order.orderId() == null || order.signature() == null) {
            return FederationIngestResult.REJECTED_BAD_SIGNATURE;
        }

        // 1 + 2：白名单与验签合并——找出公钥能验通本令的节点
        FederationNode origin = null;
        for (FederationNode node : nodes) {
            if (PenaltyVerifier.verify(order, node.publicKey())) {
                origin = node;
                break;
            }
        }
        if (origin == null) {
            log.warn("联邦入站令验签失败（无已知节点公钥可验证）：orderId={}", order.orderId());
            return FederationIngestResult.REJECTED_BAD_SIGNATURE;
        }

        // 3：幂等落库——order_id 唯一，重复投递返回 0
        int inserted = repository.insertIgnore(
                order.orderId(),
                order.subjectType().name(),
                order.subjectId(),
                order.penaltyType().name(),
                order.issuedAt(),
                order.signature(),
                origin.normalizedBaseUrl(),
                Instant.now());
        if (inserted == 0) {
            log.info("联邦入站令重复（已处理过）：orderId={}", order.orderId());
            return FederationIngestResult.DUPLICATE;
        }

        // 4：本地执行（跨群封禁等）；失败由 handler 自行吞掉，不影响"已接受"这一事实
        banHandler.onPenaltyAccepted(order);
        log.info("联邦入站令已接受：orderId={} penalty={} origin={}",
                order.orderId(), order.penaltyType(), origin.normalizedBaseUrl());
        return FederationIngestResult.ACCEPTED;
    }
}
