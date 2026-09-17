package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.core.moderation.ModerationRule;
import com.tg.heyisheng.bot.core.moderation.ModerationVerdict;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 按群教学规则检测器（模块九 §10.3 的 {@code /teach} 规则在热路径上的执行者）。
 *
 * <p><b>为什么不实现 {@code ModerationLayer}</b>：那个接口的 {@code inspect(String)} 没有 chatId，
 * 而教学规则是<b>按群</b>的（V5.0：「本群立即生效」）。本项目在按群违禁词上已经做过同样的判断
 * （见 {@code BannedWordDetector} 的类注释），本类沿用该先例——按群检测器<b>并列</b>于四层流水线，
 * 由 {@code UpdateDispatcher} 用 {@code worseOf} 取最严重，而不是硬塞进层接口。
 *
 * <p><b>多条命中取最严重</b>（照 {@code UpdateDispatcher.worseOf} 的既有纪律）：
 * 低等级命中不得掩盖高等级；任一条是硬红线即整条判为硬红线（其处置是不等复核）。
 *
 * <p><b>取数为空是常态</b>：没有任何教学规则的群，本检测器直接返回空——
 * 它的存在不该给未使用该功能的群增加任何开销（{@code rulesFor} 命中空列表缓存）。
 */
public class TaughtRuleDetector {

    private final TaughtRuleService service;

    public TaughtRuleDetector(TaughtRuleService service) {
        this.service = service;
    }

    /**
     * @param chatId 群组 ID（null 时无从取规则，直接放行）
     * @param text   消息正文（仅在内存中即时消费）
     * @return 命中的最严重结果；无命中为空
     */
    public Optional<ModerationVerdict> inspect(Long chatId, String text) {
        if (chatId == null || text == null || text.isEmpty()) {
            return Optional.empty();
        }

        RiskLevel worst = RiskLevel.NONE;
        boolean hardLine = false;
        List<String> matched = new ArrayList<>();

        for (ModerationRule rule : service.rulesFor(chatId)) {
            if (!rule.matches(text)) {
                continue;
            }
            matched.add(rule.id());
            if (rule.hardLine()) {
                hardLine = true;
            }
            if (rule.riskLevel().severity() > worst.severity()) {
                worst = rule.riskLevel();
            }
        }

        if (matched.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ModerationVerdict(worst, hardLine, matched));
    }
}
