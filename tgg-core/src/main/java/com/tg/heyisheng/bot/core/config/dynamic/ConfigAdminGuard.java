package com.tg.heyisheng.bot.core.config.dynamic;

import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 配置**写权限**判定（模块十一 扩展）。
 *
 * <p><b>为什么与「能审批」分开一层</b>：读配置与审批案件是同一批人通常没问题，但
 * <b>改配置</b>是更大的一步——它能打开/关闭整个模块、改阈值、触发重启。故写权限单独一门。
 *
 * <p><b>默认回落到复核人白名单</b>（{@code tgg.moderation.reviewers}）：满足「后台拥有配置权限」
 * 的开箱即用；要让写权限**比审批更严**，显式设 {@code tgg.admin.config-admins} 即分离。
 * 这与项目既有纪律一致——空集 fail-closed（不静默放开），并在启动期 WARN 说明后果。
 *
 * <p><b>是热的</b>：每次判定都经 {@link RuntimeConfigService} 现读，故改白名单**无需重启**。
 */
@Service
public class ConfigAdminGuard {

    /** 写权限白名单键。 */
    public static final String CONFIG_ADMINS_KEY = "tgg.admin.config-admins";

    private static final Logger log = LoggerFactory.getLogger(ConfigAdminGuard.class);

    private final RuntimeConfigService config;
    private final ModerationReviewGuard reviewers;

    public ConfigAdminGuard(RuntimeConfigService config, ModerationReviewGuard reviewers) {
        this.config = config;
        this.reviewers = reviewers;
    }

    /** 实际生效的写权限名单：显式名单非空则用它，否则回落到复核人名单。 */
    public Set<Long> configAdmins() {
        Set<Long> explicit = config.getCsvIds(CONFIG_ADMINS_KEY);
        return explicit.isEmpty() ? reviewers.reviewerIds() : explicit;
    }

    /** 该用户是否有配置写权限。未知/空一律拒绝。 */
    public boolean isConfigAdmin(Long userId) {
        return userId != null && configAdmins().contains(userId);
    }

    /**
     * 当前写权限名单的**来源**——供界面标注「现在到底是谁有权写」。
     *
     * <p>回落是刻意的默认（开箱即用），但它把「能审批」与「能改配置」绑在一起；
     * 若不把来源显式摆出来，运维会以为两者已经分离。启动日志已打印，但运维看的是后台页面。
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
                    + "配置写入与重启操作将对任何人不可用（不是「权限不足」，是无人有权）。"
                    + "要启用，请配置其一。", CONFIG_ADMINS_KEY);
        } else {
            log.info("配置写权限名单生效：{} 人（来源 {}）", configAdmins().size(),
                    config.getCsvIds(CONFIG_ADMINS_KEY).isEmpty() ? "回落 TGG_MODERATION_REVIEWERS" : CONFIG_ADMINS_KEY);
        }
    }
}
