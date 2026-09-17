package com.tg.heyisheng.bot.listing.merchant;

import java.util.HashSet;
import java.util.Set;

/**
 * 商家资质复核人判定（模块六）：<b>全局 userId 白名单</b>。
 *
 * <p><b>为什么不复用群内 RBAC</b>（照模块八 {@code FederationAdminGuard} 的取舍）：
 * 现有 {@code Role} / {@code RoleGrantParser} 是<b>群内</b>语义
 * （格式 {@code <chatId>:<userId>[:role]}），而资质复核人管理的是<b>平台</b>上的商家名录，
 * 不属于任何一个群。硬塞进群内模型会让授权源的 {@code chatId} 语义错配——
 * 「资质复核人」不该因为某个群的管理员配置变化而获得或失去权能。
 *
 * <p>门控仍在命令层（handler 内判定），只是授权源不同：配置 {@code tgg.merchant.reviewers}。
 *
 * <p><b>未知即拒绝</b>：{@code null} userId 与不在名单内一律返回 {@code false}。
 */
public class MerchantReviewGuard {

    private final Set<Long> reviewerIds;

    public MerchantReviewGuard(Iterable<Long> reviewerIds) {
        Set<Long> set = new HashSet<>();
        if (reviewerIds != null) {
            for (Long id : reviewerIds) {
                if (id != null) {
                    set.add(id);
                }
            }
        }
        this.reviewerIds = Set.copyOf(set);
    }

    /** 该用户是否为资质复核人。 */
    public boolean isReviewer(Long userId) {
        return userId != null && reviewerIds.contains(userId);
    }

    /** 已配置的复核人数量（供装配期告警与测试使用）。 */
    public int size() {
        return reviewerIds.size();
    }
}
