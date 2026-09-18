package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.core.moderation.SensitiveTopicStrikeService;

import java.util.Optional;

/**
 * 「无违规」教学门槛（模块九 §10.3）——用 {@code tgg-core} 能看到的信号判定。
 *
 * <p>这是原文三条门槛里 core 唯一能独立实现的一条：违规计数
 * （{@code sensitive_topic_strikes}）就在 core 的可见范围内。
 * 另两条的落地方式见 {@link TeachGate} 与 {@code NoDeductionTeachGate}（模块七）。
 *
 * <p><b>判定维度是「用户 × 群」</b>：与「敏感话题递进处置」用的是同一份按群计数，
 * 语义一致——「本群的教学资格」由「本群的表现」决定。
 */
public class NoViolationTeachGate implements TeachGate {

    private final SensitiveTopicStrikeService strikeService;

    public NoViolationTeachGate(SensitiveTopicStrikeService strikeService) {
        this.strikeService = strikeService;
    }

    @Override
    public Optional<String> rejectionFor(long chatId, Long userId) {
        if (userId == null) {
            return Optional.of("无法识别你的身份。");
        }
        int strikes = strikeService.countOf(chatId, userId);
        return strikes > 0
                ? Optional.of("本群教学要求无违规记录（你已有 " + strikes + " 次敏感话题违规）。")
                : Optional.empty();
    }
}
