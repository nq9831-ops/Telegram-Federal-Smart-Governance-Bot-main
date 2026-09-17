package com.tg.heyisheng.bot.core.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 复核人判定（模块九 §10.4 · 操作员推翻权）：<b>全局 userId 白名单</b>。
 *
 * <p><b>为什么不复用群内 RBAC</b>（照模块八 {@code FederationAdminGuard} / 模块六
 * {@code MerchantReviewGuard} 的取舍）：复核队列记录的是<b>跨群</b>的审核命中，复核是<b>平台层</b>
 * 动作；而 {@code Permission} / {@code Role} 是<b>群内</b>语义（{@code <chatId>:<userId>[:role]}）。
 * 硬塞进群内模型会让授权源的 {@code chatId} 语义错配——「复核人」不该因为某个群的管理员配置变化而
 * 获得或失去权能。授权源是配置 {@code tgg.moderation.reviewers}。
 *
 * <p><b>未知即拒绝</b>：{@code null} userId 与不在名单内一律 {@code false}。
 *
 * <p><b>空名单 = 命令静默不可用</b>：不配即无人可复核——启动期打 WARN 说明，
 * 沿用本项目「缺失即显式降级、不静默」的口径（与 {@code tgg.merchant.reviewers} 一致）。
 */
@Service
public class ModerationReviewGuard {

    private static final Logger log = LoggerFactory.getLogger(ModerationReviewGuard.class);

    private final Set<Long> reviewerIds;

    public ModerationReviewGuard(@Value("${tgg.moderation.reviewers:}") String reviewers) {
        this.reviewerIds = parse(reviewers);
        if (this.reviewerIds.isEmpty()) {
            log.warn("未配置 TGG_MODERATION_REVIEWERS：/review_list·/review_approve·/review_reject "
                    + "对任何人不可用（不是「权限不足」，是无响应）。要启用人工复核请注入该变量。");
        }
    }

    /** 该用户是否为复核人。 */
    public boolean isReviewer(Long userId) {
        return userId != null && reviewerIds.contains(userId);
    }

    /** 已配置的复核人数量（供装配期告警与测试使用）。 */
    public int size() {
        return reviewerIds.size();
    }

    /** 解析逗号分隔的 userId 名单；非数字项忽略并告警，不因一个笔误让整条配置失效。 */
    static Set<Long> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(token));
            } catch (NumberFormatException ex) {
                log.warn("TGG_MODERATION_REVIEWERS 含非数字项，已忽略：{}", token);
            }
        }
        return Set.copyOf(ids);
    }
}
