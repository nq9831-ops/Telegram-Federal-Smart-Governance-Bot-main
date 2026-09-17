package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.core.moderation.ModerationVerdict;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 按群违禁词检测。
 *
 * <p><b>为什么不实现 {@code ModerationLayer}</b>：那个接口的 {@code inspect(String)} 没有 chatId，
 * 而违禁词是<b>按群</b>的配置——硬塞进同一接口会迫使改签名、波及 L1 正则层与既有测试。
 * 且"通用规则层"与"群主自定义词库"本就是两种东西，各自独立更清楚。
 *
 * <p><b>匹配语义</b>：大小写不敏感的**子串**匹配（不做正则、不做分词）。
 * 中文无分词，短词（如单字）可能误伤——这是管理员的自治责任，规则需在文档写明。
 *
 * <p><b>风险等级与处置</b>：命中一律记 {@link RiskLevel#MEDIUM}（非硬红线）。
 * 注意：当前处置链对 MEDIUM 是<b>命中即删除</b>——{@code ModerationEnforcer} 对任何
 * {@code needsReview} 都返回 DeleteMessage，<b>没有"仅警告"这一档</b>，也没有按群的分级开关。
 * 换言之，"删还是警告"目前不由群主逐条决定；分级处置属后续增量。
 */
public class BannedWordDetector {

    /** 命中时记入判定的规则 id（审计用，稳定值）。 */
    static final String RULE_ID = "BANNED_WORD";

    private final BannedWordService service;

    public BannedWordDetector(BannedWordService service) {
        this.service = service;
    }

    /**
     * 按群检测文本是否含违禁词。
     *
     * @param chatId 群组 ID（null 时无从查词表，直接放行）
     * @param text   待检测文本（null/空直接放行）
     * @return 命中时的判定结果；未命中为空
     */
    public Optional<ModerationVerdict> inspect(Long chatId, String text) {
        if (chatId == null || text == null || text.isEmpty()) {
            return Optional.empty();
        }
        List<String> words = service.listWords(chatId);
        if (words.isEmpty()) {
            return Optional.empty();
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            if (haystack.contains(word.toLowerCase(Locale.ROOT))) {
                return Optional.of(new ModerationVerdict(RiskLevel.MEDIUM, false, List.of(RULE_ID)));
            }
        }
        return Optional.empty();
    }
}
