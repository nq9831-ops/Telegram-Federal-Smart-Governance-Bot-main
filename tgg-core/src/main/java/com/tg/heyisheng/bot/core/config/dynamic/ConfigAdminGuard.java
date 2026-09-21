package com.tg.heyisheng.bot.core.config.dynamic;

import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import com.tg.heyisheng.bot.core.platform.PlatformGrantSource;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 配置**写权限**判定（模块十一 扩展）。
 *
 * <p><b>为什么与「能审批」分开一层</b>：读配置与审批案件是同一批人通常没问题，但
 * <b>改配置</b>是更大的一步——它能打开/关闭整个模块、改阈值、触发重启。故写权限单独一门。
 *
 * <p><b>授权源两层（模块十一 · 权限模型）</b>：优先查平台账本
 * （{@link PlatformPermission#CONFIG_WRITE}），无记录时回落到
 * {@code tgg.admin.config-admins} → 再回落 {@code tgg.moderation.reviewers}——升级不破坏线上授权。
 *
 * <p><b>主体带类型</b>：见 {@link ModerationReviewGuard} 的同款说明；配置键只对 TG 主体回落。
 */
@Service
public class ConfigAdminGuard {

    /** 写权限白名单键。 */
    public static final String CONFIG_ADMINS_KEY = "tgg.admin.config-admins";

    private static final Logger log = LoggerFactory.getLogger(ConfigAdminGuard.class);

    private final RuntimeConfigService config;
    private final ModerationReviewGuard reviewers;
    private final PlatformGrantSource grants;

    /** 生产构造器：热读取 + 平台账本。 */
    @Autowired
    public ConfigAdminGuard(RuntimeConfigService config, ModerationReviewGuard reviewers,
                            PlatformGrantSource grants) {
        this.config = config;
        this.reviewers = reviewers;
        this.grants = grants;
    }

    /** 兼容构造器（单测 / 无账本上下文）。 */
    public ConfigAdminGuard(RuntimeConfigService config, ModerationReviewGuard reviewers) {
        this(config, reviewers, null);
    }

    /** 实际生效的写权限名单：显式名单非空则用它，否则回落到复核人名单。 */
    public Set<Long> configAdmins() {
        Set<Long> explicit = config.getCsvIds(CONFIG_ADMINS_KEY);
        return explicit.isEmpty() ? reviewers.reviewerIds() : explicit;
    }

    /** Telegram 侧判定（主体恒为 TG 用户）。 */
    public boolean isConfigAdmin(Long userId) {
        return isConfigAdmin(ActorType.TG_USER, userId);
    }

    /** 带主体类型的判定（Web 侧用）。未知/空一律拒绝。 */
    public boolean isConfigAdmin(ActorType subjectType, Long subjectId) {
        if (subjectId == null) {
            return false;
        }
        if (grants != null
                && grants.hasPermission(subjectType, subjectId, PlatformPermission.CONFIG_WRITE)) {
            return true;
        }
        return subjectType == ActorType.TG_USER && configAdmins().contains(subjectId);
    }

    /**
     * 当前写权限名单的**来源**——供界面标注「现在到底是谁有权写」。
     *
     * @return {@code "explicit"}（显式配置了 {@code tgg.admin.config-admins}）
     *         或 {@code "reviewers"}（为空，回落复核人名单）
     */
    public String source() {
        return config.getCsvIds(CONFIG_ADMINS_KEY).isEmpty() ? "reviewers" : "explicit";
    }

    /** 无人可写时显式告警——否则运维会以为「后台能改配置」，实际点了按钮拿 403。 */
    @PostConstruct
    void warnIfNobodyCanWrite() {
        if (configAdmins().isEmpty()) {
            log.warn("无可写配置者：{} 与 TGG_MODERATION_REVIEWERS 均为空——"
                    + "配置写入与重启操作将对任何人不可用（超管除外，超管天然全权）。"
                    + "要启用，请配置其一，或由超管在后台账本授予 CONFIG_WRITE。", CONFIG_ADMINS_KEY);
        } else {
            log.info("配置写权限名单生效：{} 人（来源 {}）", configAdmins().size(),
                    config.getCsvIds(CONFIG_ADMINS_KEY).isEmpty() ? "回落 TGG_MODERATION_REVIEWERS" : CONFIG_ADMINS_KEY);
        }
    }
}
