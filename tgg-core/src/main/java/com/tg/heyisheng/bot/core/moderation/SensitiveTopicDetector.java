package com.tg.heyisheng.bot.core.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 敏感话题分级检测器（模块九 §10.5）——<b>按群</b>，带群组标签豁免。
 *
 * <p><b>为什么「按群」</b>：豁免依据是「本群声明了哪些话题标签」，因此必须拿到 chatId。
 * 这与 {@code BannedWordDetector} / {@code TaughtRuleDetector} 同属「按群检测器**并列于**层接口」
 * ——{@code ModerationLayer.inspect(String)} 无 chatId，装不下按群语义（照 {@code BannedWordDetector:13}
 * 的既有判断）。
 *
 * <p><b>豁免语义</b>：本群声明了某话题标签（如 {@code politics}）时，该话题的命中<b>不上报</b>
 * ——这正是「群主自治」。但 V5.0 §10.5 明确**不可豁免内容：恐怖活动 / 极端主义 / 煽动战争**
 * （实现为 {@link Topic#exemptable()} 为 false 的话题，声明标签也照报）。
 * <b>红线同样不受豁免</b>——红线走独立通路（{@code BuiltInRules}），本检测器只产出非 hardLine 判定。
 *
 * <p><b>默认话题集</b>：以下为起步集，分级保守（多为 MEDIUM/LOW）；命中即「删除 + 入队复核」，
 * 不会自动封禁（{@code hardLine=false}）。
 */
public class SensitiveTopicDetector {

    /**
     * 一个敏感话题：豁免用的标签名 + 分级 + {@code exemptable}（是否可被群标签豁免）+ 触发模式。
     *
     * @param exemptable false = <b>不可豁免</b>（V5.0 §10.5：恐怖活动 / 极端主义 / 煽动战争）
     */
    public record Topic(String tag, RiskLevel level, boolean exemptable, Pattern pattern) {
    }

    /**
     * 起步话题集——<b>以 V5.0 原文 §10.5 为准</b>：
     * 分类定义 = 恐怖活动 / 宗教主义 / 国际政治 / 政治；
     * 不可豁免 = 恐怖活动 / 极端主义 / 煽动战争（实现为 {@code terrorism} 话题且 {@code exemptable=false}）。
     *
     * <p>标签名即 {@code group_topic_tags.tag}，声明它即可豁免**可豁免**话题；
     * 命中的规则 id 为 {@code SENSITIVE_<TAG>}（供审计与规则效果统计）。
     *
     * <p><b>注意</b>：话题词一旦写松，正常发言会被大量误删（分级处置含删除），
     * 故四个模式都要求明确的**行为 / 主张**语汇，而非单个宽泛名词。
     */
    static final List<Topic> DEFAULT_TOPICS = List.of(
            // 不可豁免：恐怖活动 / 极端主义 / 煽动战争
            new Topic("terrorism", RiskLevel.HIGH, false,
                    Pattern.compile("(?i)(恐怖袭击|恐怖组织|圣战|加入isis|加入伊斯兰国|自制炸弹|炸弹制作|爆炸物制作"
                            + "|煽动战争|极端主义|宣扬暴力|恐怖主义|al[- ]?qaeda)")),
            new Topic("religionism", RiskLevel.MEDIUM, true,
                    Pattern.compile("(?i)(宗教极端|宗教主义|教派仇杀|迫害异教徒|异教讨伐|圣战分子)")),
            new Topic("intl_politics", RiskLevel.MEDIUM, true,
                    Pattern.compile("(?i)(国际政治|地缘政治|联合国决议|制裁决议|台海局势|俄乌战争|巴以冲突)")),
            new Topic("politics", RiskLevel.LOW, true,
                    Pattern.compile("(?i)(政治|选举|游行|抗议|示威|颠覆政权)"))
    );

    /**
     * 已定义话题的标签名（供命令层校验「这个标签到底会不会产生豁免」）。
     *
     * <p><b>为什么需要它</b>：`/group_tag add` 若对任意合法字符集的标签都回执「豁免已生效」，
     * 就是**弱代理为真、强谓词为假**——回执成功但豁免永不生效，运维会被误导。
     */
    public static Set<String> knownTags() {
        return DEFAULT_TOPICS.stream().map(Topic::tag).collect(Collectors.toUnmodifiableSet());
    }

    /** 该标签是否为**可豁免**的已定义话题（未知标签与不可豁免话题都返回 false）。 */
    public static boolean isExemptableTag(String tag) {
        if (tag == null) {
            return false;
        }
        String clean = tag.trim().toLowerCase(Locale.ROOT);
        return DEFAULT_TOPICS.stream().anyMatch(topic -> topic.exemptable() && topic.tag().equals(clean));
    }

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
            if (topic.exemptable() && exempted.contains(topic.tag())) {
                continue; // 群标签豁免：本群已声明该话题（**不可豁免**话题不看标签）
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
