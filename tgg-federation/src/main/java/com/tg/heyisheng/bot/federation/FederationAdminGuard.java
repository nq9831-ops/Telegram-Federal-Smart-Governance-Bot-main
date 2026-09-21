package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import com.tg.heyisheng.bot.core.platform.PlatformGrantSource;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;

import java.util.HashSet;
import java.util.Set;

/**
 * 联邦管理员判定（模块八）：<b>全局 userId 白名单</b>。
 *
 * <p><b>为什么不复用群内 RBAC</b>：既有 {@code Role} / {@code RoleGrantParser} 是**群内**语义
 * （格式 {@code <chatId>:<userId>[:role]}），而联邦管理员是**跨群/全局**角色，
 * 故用独立白名单（配置 {@code tgg.federation.admins}）。
 *
 * <p><b>授权源两层（模块十一 · 权限模型）</b>：优先查平台账本
 * （{@link PlatformPermission#FEDERATION_ADMIN}），无记录时回落配置键。
 *
 * <p><b>主体带类型</b>：见 {@code ModerationReviewGuard} 的同款说明；配置键只对 TG 主体回落。
 */
public class FederationAdminGuard {

    /** 配置键（热读取）。 */
    public static final String KEY = "tgg.federation.admins";

    private final RuntimeConfigService config;
    private final Set<Long> fixedIds;
    private final PlatformGrantSource grants;

    /** 热模式：调用期读取 + 平台账本。 */
    public FederationAdminGuard(RuntimeConfigService config, PlatformGrantSource grants) {
        this.config = config;
        this.fixedIds = Set.of();
        this.grants = grants;
    }

    /** 兼容（无账本）。 */
    public FederationAdminGuard(RuntimeConfigService config) {
        this(config, null);
    }

    /** 静态模式（单测 / 固定名单）：立即复制，行为同改造前。 */
    public FederationAdminGuard(Iterable<Long> adminIds) {
        Set<Long> set = new HashSet<>();
        if (adminIds != null) {
            for (Long id : adminIds) {
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
    public boolean isAdmin(Long userId) {
        return isAdmin(ActorType.TG_USER, userId);
    }

    /** 带主体类型的判定（Web 侧用）。 */
    public boolean isAdmin(ActorType subjectType, Long subjectId) {
        if (subjectId == null) {
            return false;
        }
        if (grants != null
                && grants.hasPermission(subjectType, subjectId, PlatformPermission.FEDERATION_ADMIN)) {
            return true;
        }
        return subjectType == ActorType.TG_USER && adminIds().contains(subjectId);
    }

    /** 已配置的联邦管理员数量（供装配期告警与测试使用）。 */
    public int size() {
        return adminIds().size();
    }

    private Set<Long> adminIds() {
        return config == null ? fixedIds : config.getCsvIds(KEY);
    }
}
