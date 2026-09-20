package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;

import java.util.Set;

/**
 * 「复核合规」类的可见性接缝（core 侧）：复核队列与合规证据是**平台层**动作，
 * 授权源是全局白名单 {@code tgg.moderation.reviewers}（{@link ModerationReviewGuard}）。
 *
 * <p><b>为什么这些命令进不了注册表判定</b>：它们的 {@code @BotCommand.requiredPermission}
 * 是 {@code NONE}（{@code Permission}/{@code Role} 是群内模型，装不下跨群的平台角色），
 * 门控写在各自 handler 里。于是 {@code /menu} 无从判断「此人是否可见」——本接缝补上这一判定。
 *
 * <p><b>判定必须与执行同源</b>：这里调的 {@code isReviewer} 与各 handler 内用的是**同一个 Guard
 * 的同一个方法**。若两处各写一份条件，迟早漂移成「菜单看得见、点了不生效」或（更糟）
 * 「对无权者暴露命令存在」。
 *
 * <p>{@code data_breach} 也在本接缝内：它与复核队列共用同一份白名单（见
 * {@code DataBreachCommandHandler} 的 javadoc）。
 */
public class ModerationMenuVisibility implements MenuVisibility {

    /** 本接缝负责的命令——与调 {@code ModerationReviewGuard.isReviewer} 的 handler 一一对应。 */
    static final Set<String> COMMANDS =
            Set.of("review_list", "review_approve", "review_reject", "data_breach");

    private final ModerationReviewGuard guard;

    public ModerationMenuVisibility(ModerationReviewGuard guard) {
        this.guard = guard;
    }

    @Override
    public Set<String> commands() {
        return COMMANDS;
    }

    @Override
    public boolean visible(long chatId, Long userId) {
        // 白名单是全局的，与群无关——chatId 不参与判定（保留形参是为了接口统一）
        return guard.isReviewer(userId);
    }
}
