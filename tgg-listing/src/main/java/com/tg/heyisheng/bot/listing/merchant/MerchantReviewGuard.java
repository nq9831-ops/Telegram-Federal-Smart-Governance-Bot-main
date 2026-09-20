package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;

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
 *
 * <p><b>热生效</b>：生产构造器经 {@link RuntimeConfigService} <b>调用期</b>读取名单；
 * 另一构造器为静态模式（单测 / 固定名单），行为同改造前。
 */
public class MerchantReviewGuard {

    /** 配置键（热读取）。 */
    public static final String KEY = "tgg.merchant.reviewers";

    private final RuntimeConfigService config;
    private final Set<Long> fixedIds;

    /** 热模式：调用期读取。 */
    public MerchantReviewGuard(RuntimeConfigService config) {
        this.config = config;
        this.fixedIds = Set.of();
    }

    /** 静态模式（单测 / 固定名单）：立即复制，行为同改造前。 */
    public MerchantReviewGuard(Iterable<Long> reviewerIds) {
        Set<Long> set = new HashSet<>();
        if (reviewerIds != null) {
            for (Long id : reviewerIds) {
                if (id != null) {
                    set.add(id);
                }
            }
        }
        this.config = null;
        this.fixedIds = Set.copyOf(set);
    }

    /** 该用户是否为资质复核人。 */
    public boolean isReviewer(Long userId) {
        return userId != null && reviewerIds().contains(userId);
    }

    /** 已配置的复核人数量（供装配期告警与测试使用）。 */
    public int size() {
        return reviewerIds().size();
    }

    private Set<Long> reviewerIds() {
        return config == null ? fixedIds : config.getCsvIds(KEY);
    }
}
