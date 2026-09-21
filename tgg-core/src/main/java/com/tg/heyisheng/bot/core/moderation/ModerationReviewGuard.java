package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import com.tg.heyisheng.bot.core.platform.PlatformGrantSource;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 复核人判定（模块九 §10.4 · 操作员推翻权）。
 *
 * <p><b>为什么不复用群内 RBAC</b>（照模块八/模块六的取舍）：复核队列记录的是<b>跨群</b>的审核命中，
 * 复核是<b>平台层</b>动作；而 {@code Permission} / {@code Role} 是<b>群内</b>语义。授权源是配置
 * {@code tgg.moderation.reviewers}（TG userId 白名单）。
 *
 * <p><b>授权源现为两层（模块十一 · 权限模型）</b>：优先查平台账本
 * {@link PlatformGrantSource}（超管授予的 {@link PlatformPermission#REVIEW_DECIDE}），
 * 账本无记录时**回落**原有配置键——升级不破坏线上授权。
 *
 * <p><b>主体带类型</b>：后台账号 id 与 TG userId 数值空间重叠，故判定按 {@code (类型, id)}。
 * Telegram 侧调用沿用 {@link #isReviewer(Long)}（委托 {@link ActorType#TG_USER}）；
 * Web 侧用 {@link #isReviewer(ActorType, Long)}（主体来自会话）。
 * <b>配置键只对 TG 主体回落</b>——它存的是 userId，账号 id 撞上不得误放行。
 */
@Service
public class ModerationReviewGuard {

    private static final Logger log = LoggerFactory.getLogger(ModerationReviewGuard.class);

    /** 配置键（热读取）。 */
    public static final String KEY = "tgg.moderation.reviewers";

    private final RuntimeConfigService config;
    private final Set<Long> fixedIds;
    private final PlatformGrantSource grants;

    /** 生产构造器：热读取配置 + 平台账本。 */
    @Autowired
    public ModerationReviewGuard(RuntimeConfigService config, PlatformGrantSource grants) {
        this.config = config;
        this.fixedIds = Set.of();
        this.grants = grants;
    }

    /** 兼容构造器：仅配置（无账本）——供不依赖账本的上下文与单测使用。 */
    public ModerationReviewGuard(RuntimeConfigService config) {
        this(config, null);
    }

    /** 静态模式（单测 / 固定名单）：立即解析，行为同改造前。 */
    public ModerationReviewGuard(String reviewers) {
        this.config = null;
        this.fixedIds = parse(reviewers);
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
                && grants.hasPermission(subjectType, subjectId, PlatformPermission.REVIEW_DECIDE)) {
            return true;
        }
        // 配置键存的是 TG userId——只对 TG 主体回落
        return subjectType == ActorType.TG_USER && reviewerIds().contains(subjectId);
    }

    /** 已配置的复核人（热读取；静态模式下为构造时解析的结果）。 */
    public Set<Long> reviewerIds() {
        return config == null ? fixedIds : config.getCsvIds(KEY);
    }

    /** 已配置的复核人数量（供装配期告警与测试使用）。 */
    public int size() {
        return reviewerIds().size();
    }

    /** 名单为空时启动告警——否则复核命令会以「无响应」的样子静默失效。 */
    @PostConstruct
    void warnIfEmpty() {
        if (reviewerIds().isEmpty()) {
            log.warn("未配置 TGG_MODERATION_REVIEWERS：/review_list·/review_approve·/review_reject "
                    + "对任何人不可用（不是「权限不足」，是无响应）。要启用人工复核请注入该变量，"
                    + "或由超管在后台账本授予 REVIEW_DECIDE。");
        }
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
