package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import com.tg.heyisheng.bot.core.platform.PlatformGrantSource;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;

import java.util.HashSet;
import java.util.Set;

/**
 * 商家资质复核人判定（模块六）：<b>全局 userId 白名单</b>。
 *
 * <p><b>为什么不复用群内 RBAC</b>（照模块八的取舍）：{@code Role} / {@code RoleGrantParser} 是
 * <b>群内</b>语义，而资质复核人管理的是<b>平台</b>上的商家名录，不属于任何一个群。
 *
 * <p><b>授权源两层（模块十一 · 权限模型）</b>：优先查平台账本
 * （{@link PlatformPermission#MERCHANT_REVIEW}），无记录时回落配置 {@code tgg.merchant.reviewers}。
 *
 * <p><b>主体带类型</b>：见 {@code ModerationReviewGuard} 的同款说明；配置键只对 TG 主体回落。
 */
public class MerchantReviewGuard {

    /** 配置键（热读取）。 */
    public static final String KEY = "tgg.merchant.reviewers";

    private final RuntimeConfigService config;
    private final Set<Long> fixedIds;
    private final PlatformGrantSource grants;

    /** 热模式：调用期读取 + 平台账本。 */
    public MerchantReviewGuard(RuntimeConfigService config, PlatformGrantSource grants) {
        this.config = config;
        this.fixedIds = Set.of();
        this.grants = grants;
    }

    /** 兼容（无账本）。 */
    public MerchantReviewGuard(RuntimeConfigService config) {
        this(config, null);
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
        this.grants = null;
    }

    /** Telegram 侧判定（主体恒为 TG 用户）。 */
    public boolean isReviewer(Long userId) {
        return isReviewer(ActorType.TG_USER, userId);
    }

    /** 带主体类型的判定（Web 侧用）。 */
    public boolean isReviewer(ActorType subjectType, Long subjectId) {
        if (subjectId == null) {
            return false;
        }
        if (grants != null
                && grants.hasPermission(subjectType, subjectId, PlatformPermission.MERCHANT_REVIEW)) {
            return true;
        }
        return subjectType == ActorType.TG_USER && reviewerIds().contains(subjectId);
    }

    /** 已配置的复核人数量（供装配期告警与测试使用）。 */
    public int size() {
        return reviewerIds().size();
    }

    private Set<Long> reviewerIds() {
        return config == null ? fixedIds : config.getCsvIds(KEY);
    }
}
