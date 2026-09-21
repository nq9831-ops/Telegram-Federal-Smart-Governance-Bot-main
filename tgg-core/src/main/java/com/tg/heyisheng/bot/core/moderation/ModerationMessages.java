package com.tg.heyisheng.bot.core.moderation;

/**
 * 审核域（模块九：复核队列 / 话题标签 / 敏感话题分级 / 红线 SLA / 处置告知）的**用户可见文案**
 * ——{@code *Messages} 家族在 moderation 包的落点。
 *
 * <p><b>措辞冻结</b>：所有字符串**逐字节**取自搬迁前的原实现（含全角标点、换行与 emoji），
 * 搬迁不改一个字节——证据是现有逐字断言与「含中文字面量集合哈希不变」的比对。
 *
 * <p>组织规则见 {@code VOICE.md} 第八节：整句用常量；含插值的整行/整段用静态方法
 * （如 {@link #reviewListLine} 承载「群 / 规则 / 等级」三处插值），不硬拆碎片常量。
 */
public final class ModerationMessages {

    private ModerationMessages() {
    }

    // ────────────── /group_tag ──────────────

    /** {@code /group_tag} 用法。 */
    public static final String GROUPTAG_USAGE = "用法：/group_tag add 标签（remove 删除 / list 列出）\n"
            + "例：/group_tag add gambling —— 声明本群为赌博话题群，敏感话题分级对赌博豁免（红线不豁免）。";

    /** 标签未生效前缀（后接原因）。 */
    public static final String GROUPTAG_REJECTED_PREFIX = "标签未生效：";

    /** 标签属已知但不可豁免的话题。 */
    public static final String GROUPTAG_NOT_EXEMPTABLE_KNOWN =
            "该话题属不可豁免内容（恐怖活动 / 极端主义 / 煽动战争）。";

    /** 标签不是已定义话题。 */
    public static final String GROUPTAG_NOT_EXEMPTABLE_UNKNOWN =
            "该标签不是已定义话题，不会产生任何豁免。";

    /** 已声明该标签。 */
    public static final String GROUPTAG_ALREADY_DECLARED = "本群已声明该标签（无需重复）。";

    /** 移除时本群没有该标签。 */
    public static final String GROUPTAG_REMOVE_MISSING = "本群没有该标签。";

    /** 本群未声明任何标签。 */
    public static final String GROUPTAG_LIST_EMPTY = "本群未声明任何话题标签。";

    /** 标签列表标题前缀（后接条数）。 */
    public static final String GROUPTAG_LIST_HEAD_PREFIX = "本群话题标签（";

    /** 标签列表标题后缀。 */
    public static final String GROUPTAG_LIST_HEAD_SUFFIX = "）：\n";

    /** 标签列表脚注。 */
    public static final String GROUPTAG_LIST_FOOTER = "（命中这些话题的敏感分级被豁免；红线不豁免。）";

    /** 标签已记录但不产生豁免时的回执。 */
    public static String groupTagRecordedNotExemptable(String tag, String why, String exemptableCsv) {
        return "标签「" + tag + "」已记录，但" + why
                + "\n可豁免话题：" + exemptableCsv;
    }

    /** 标签声明成功的回执。 */
    public static String groupTagDeclared(String tag) {
        return "已声明本群话题标签：" + tag + "（该话题的敏感分级对本群豁免；红线不受影响）。";
    }

    /** 标签移除成功的回执。 */
    public static String groupTagRemoved(String tag) {
        return "已移除本群话题标签：" + tag + "。";
    }

    // ────────────── /review_approve · /review_reject ──────────────

    /** {@code /review_approve} 用法。 */
    public static final String REVIEW_APPROVE_USAGE = "用法：/review_approve 编号\n"
            + "例：/review_approve 7（编号后也可再跟一句备注，如 /review_approve 7 确认违规）\n"
            + "备注是裁决理由，请勿粘贴消息正文（备注会落库）。";

    /** {@code /review_reject} 用法。 */
    public static final String REVIEW_REJECT_USAGE = "用法：/review_reject 编号\n"
            + "例：/review_reject 7（编号后也可再跟一句备注，如 /review_reject 7 误报）\n"
            + "备注是裁决理由，请勿粘贴消息正文（备注会落库）。";

    /** 裁决未生效前缀（后接原因）。 */
    public static final String REVIEW_REJECTED_PREFIX = "裁决未生效：";

    /** 结论动词：维持。 */
    public static final String VERB_APPROVE = "维持";

    /** 结论动词：推翻。 */
    public static final String VERB_REJECT = "推翻";

    /** 编号不存在。 */
    public static String reviewNotFound(long id) {
        return "未找到复核编号 " + id + "。";
    }

    /** 不能裁决自己的案件。 */
    public static String reviewSelfDecision(long id) {
        return "不能裁决自己的案件：复核 #" + id + " 的当事人就是你。请让其他复核人处理。";
    }

    /** 已是终态结论。 */
    public static String reviewAlreadyDecided(long id, ReviewStatus status) {
        return "复核 #" + id + " 已是终态结论（" + status + "），本次未改动。";
    }

    /** 裁决成功。{@code unfrozeMention} 为真时补一句「已触发解封」。 */
    public static String reviewDecided(long id, String verb, ReviewStatus status, boolean unfrozeMention) {
        return "复核 #" + id + " 已" + verb + "：" + status
                + (unfrozeMention ? "（若原判为硬红线，已触发解封）" : "。");
    }

    // ────────────── /review_list ──────────────

    /** 没有待复核条目。 */
    public static final String REVIEW_LIST_EMPTY = "当前没有待复核的审核命中。";

    /** 列表标题前缀（后接条数）。 */
    public static final String REVIEW_LIST_HEAD_PREFIX = "待复核（共 ";

    /** 列表标题后缀。 */
    public static final String REVIEW_LIST_HEAD_SUFFIX = " 条）：\n";

    /** 列表截断提示。 */
    public static final String REVIEW_LIST_TRUNCATED = "…（已截断，请先处理列表中的条目）\n";

    /**
     * 待复核列表的一行（含编号 / 群 / 规则 / 等级 / 硬红线标记四处插值）。
     *
     * <p><b>形参刻意用装箱类型</b>：原实现是 {@code "#" + item.getId()} 这种字符串拼接，
     * 对 {@code null} 容忍（渲染成 "null"）；若这里声明为 {@code long}，遇到 null 会**拆箱抛 NPE**
     * ——那是行为变更，不是搬迁。逐字保持原语义。
     */
    public static String reviewListLine(Long id, Long chatId, String ruleIds, String level, boolean hardLine) {
        return "#" + id
                + " · 群 " + chatId
                + " · 规则 " + ruleIds
                + " · " + level
                + (hardLine ? " · 硬红线(已封禁)" : "")
                + "\n";
    }

    // ────────────── 处置告知（删除 / 冻结 / 敏感话题 / 红线 SLA）──────────────

    /** 普通命中的群内告知（含「有疑问找谁」，不透露命中规则）。 */
    public static final String DELETED_NOTICE =
            "⚠️ 该消息命中本群内容规则，已被删除。如对判定有疑问，请联系群管理员。";

    /** 硬红线的群内告知（删除 + 封禁一并说清）。 */
    public static final String FROZEN_NOTICE =
            "⚠️ 该消息命中本群硬性红线，已被删除，发布者已被封禁。如对判定有疑问，请联系群管理员。";

    /**
     * 普通命中的群内告知（**带案件号与申诉入口**）。
     *
     * <p><b>为什么要给出编号</b>：告知是当事人唯一的解释面，而「认为判错了」必须有可执行的去处——
     * 没有编号就无法申诉（{@code /case_appeal <编号>}）。编号本身不泄露新信息：告知已经公开了
     * 「这条消息被处理」，而申诉端会校验「只有当事人能申诉」，故全群看到编号也无妨。
     *
     * <p>无编号时（入队失败 / 未装配队列 / clean）用 {@link #DELETED_NOTICE} 降级——
     * 绝不让 {@code null} 渲染进文案。
     */
    public static String deletedNoticeWithCase(Object caseId) {
        return "⚠️ 该消息命中本群内容规则，已被删除（案件 #" + caseId
                + "）。如认为判定有误，可发送 /case_appeal " + caseId + " 附上你的理由申诉。";
    }

    /** 硬红线的群内告知（带案件号与申诉入口）。无编号时降级用 {@link #FROZEN_NOTICE}。 */
    public static String frozenNoticeWithCase(Object caseId) {
        return "⚠️ 该消息命中本群硬性红线，已被删除，发布者已被封禁（案件 #" + caseId
                + "）。如认为判定有误，可发送 /case_appeal " + caseId + " 附上你的理由申诉。";
    }

    /** 敏感话题首次命中的群内警告。 */
    public static final String SENSITIVE_TOPIC_WARNING =
            "⚠️ 本群已开启敏感话题分级：该内容已被处理。再次发布将禁言 24 小时。";

    /** 敏感话题禁言的本人通知。 */
    public static String sensitiveTopicMuted(int strike) {
        return "你在本群因敏感话题被禁言 24 小时（累计第 " + strike + " 次）。";
    }

    /** 红线复核 SLA 超时的催办（发给复核人，紧急级）。{@code id} 用装箱类型，保持原拼接对 null 的容忍。 */
    public static String redlineSlaOverdue(long slaHours, Long id, String ruleIds) {
        return "⚠️ 硬红线复核超时（SLA " + slaHours + " 小时）：复核 #"
                + id + " 仍未裁决（规则 " + ruleIds + "）。"
                + "请在 /review_list 中裁决：/review_approve 维持 或 /review_reject 推翻并解封。";
    }
}
