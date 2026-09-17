package com.tg.heyisheng.bot.core.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 敏感话题分级检测器（模块九 §10.5）——<b>按群</b>，带群组标签豁免。
 *
 * <p><b>为什么「按群」</b>：豁免依据是「本群声明了哪些话题标签」，因此必须拿到 chatId。
 * 这与 {@code BannedWordDetector} / {@code TaughtRuleDetector} 同属「按群检测器**并列于**层接口」
 * ——{@code ModerationLayer.inspect(String)} 无 chatId，装不下按群语义（照 {@code BannedWordDetector:13}
 * 的既有判断）。
 *
 * <p><b>豁免语义</b>：本群声明了某话题标签（如 {@code gambling}）时，该话题的命中<b>不上报</b>
 * ——这正是「群主自治」：赌博讨论群不该因为聊赌博被自己的机器人处置。
 * <b>红线不受豁免</b>——红线走独立通路（{@code BuiltInRules}），本检测器只产出非 hardLine 判定。
 *
 * <p><b>默认话题集</b>：以下为起步集，分级保守（多为 MEDIUM/LOW）；命中即「删除 + 入队复核」，
 * 不会自动封禁（{@code hardLine=false}）。
 */
public class SensitiveTopicDetector {

    /** 一个敏感话题：豁免用的标签名 + 分级 + 触发模式。 */
    public record Topic(String tag, RiskLevel level, Pattern pattern) {
    }

    /**
     * 起步话题集。
     *
     * <p>标签名即 {@code group_topic_tags.tag}，声明它即可豁免该话题；
     * 命中的规则 id 为 {@code SENSITIVE_<TAG>}（供审计与规则效果统计）。
     */
    static final List<Topic> DEFAULT_TOPICS = List.of(
            new Topic("gambling", RiskLevel.MEDIUM,
                    Pattern.compile("(?i)(赌场|博彩|六合彩|赌球|百家乐|下注|彩票代购|casino|betting)")),
            new Topic("adult", RiskLevel.MEDIUM,
                    Pattern.compile("(?i)(成人内容|色情|约炮|裸聊|福利姬|onlyfans|porn)")),
            new Topic("finance", RiskLevel.MEDIUM,
                    Pattern.compile("(?i)(荐股|带单|喊单|投资群|内部消息|涨停|杀猪盘)")),
            new Topic("politics", RiskLevel.LOW,
                    Pattern.compile("(?i)(政治|选举|游行|抗议|示威|颠覆政权)")),
            new Topic("religion", RiskLevel.LOW,
                    Pattern.compile("(?i)(传教|布道|洗礼|皈依|异端)")),
            new Topic("medical", RiskLevel.LOW,
                    Pattern.compile("(?i)(处方药|代购药|特效药|偏方|包治百病)"))
    );

    private final GroupTopicTagService tags;
    private final List<Topic> topics;

    public SensitiveTopicDetector(GroupTopicTagService tags) {
        this(tags, DEFAULT_TOPICS);
    }

    SensitiveTopicDetector(GroupTopicTagService tags, List<Topic> topics) {
        this.tags = tags;
        this.topics = List.copyOf(topics);
    }

    /**
     * 检测本群内容是否命中敏感话题（已扣掉被标签豁免的部分）。
     *
     * @return 命中时给出判定（等级取命中话题最高者，{@code hardLine=false}）；无命中返回空
     */
    public Optional<ModerationVerdict> inspect(long chatId, String content) {
        if (content == null || content.isEmpty()) {
            return Optional.empty();
        }
        Set<String> exempted = tags.tagsOf(chatId);
        RiskLevel worst = RiskLevel.NONE;
        List<String> hitRuleIds = new ArrayList<>();
        for (Topic topic : topics) {
            if (!topic.pattern().matcher(content).find()) {
                continue;
            }
            if (exempted.contains(topic.tag())) {
                continue; // 群标签豁免：本群已声明该话题
            }
            hitRuleIds.add("SENSITIVE_" + topic.tag().toUpperCase(Locale.ROOT));
            if (topic.level().severity() > worst.severity()) {
                worst = topic.level();
            }
        }
        if (worst == RiskLevel.NONE) {
            return Optional.empty();
        }
        // hardLine 恒为 false：敏感话题是「分级」不是「红线」，绝不由本检测器触发封禁。
        return Optional.of(new ModerationVerdict(worst, false, hitRuleIds));
    }
}
