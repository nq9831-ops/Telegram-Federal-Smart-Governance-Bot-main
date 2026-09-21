package com.tg.heyisheng.bot.core.wordfilter;

/**
 * 词表与教学规则（模块三 · 违禁词 / 模块九 §10.3 · 教学规则）的**用户可见文案**——
 * {@code *Messages} 家族在 wordfilter 包的落点。
 *
 * <p><b>措辞冻结</b>：所有字符串**逐字节**取自搬迁前的原实现（含全角标点、换行与转义），
 * 搬迁不改一个字节——证据是现有逐字断言与「含中文字面量集合哈希不变」的比对。
 *
 * <p>组织规则见 {@code VOICE.md} 第八节：整句用常量、含插值的用静态方法、纯格式胶水留原处。
 */
public final class WordFilterMessages {

    private WordFilterMessages() {
    }

    // ────────────── /addword ──────────────

    /** {@code /addword} 用法。 */
    public static final String ADDWORD_USAGE = "用法：/addword 违禁词\n例：/addword 加微信";

    /** 添加成功。 */
    public static final String ADDWORD_ADDED = "记下了，这个词以后会被拦。";

    /** 已存在或无效。 */
    public static final String ADDWORD_IGNORED = "这个词已经在了，或者无效。";

    // ────────────── /delword ──────────────

    /** {@code /delword} 用法。 */
    public static final String DELWORD_USAGE = "用法：/delword 违禁词\n例：/delword 加微信";

    /** 删除成功。 */
    public static final String DELWORD_REMOVED = "删掉了。";

    /** 词不存在。 */
    public static final String DELWORD_NOT_FOUND = "本群没有这个词。";

    // ────────────── /teach ──────────────

    /** {@code /teach} 用法。 */
    public static final String TEACH_USAGE =
            "用法：/teach 规则id 正则 描述\n例：/teach SCAM_AIRDROP \"免费空投\\\\d+\" 假空投骗局";

    /** 规则未生效前缀（后接原因）。 */
    public static final String TEACH_REJECTED_PREFIX = "规则未生效：";

    /** 教学成功回执（回显落库后的正则，即「确认」步骤）。 */
    public static String teachAccepted(String ruleId, String regex, String name) {
        return "规则已经生效（本群立即起效）：\n"
                + "编号：" + ruleId + "\n"
                + "正则：" + regex + "\n"
                + "描述：" + name + "\n"
                + "命中后：判为 MEDIUM，进入人工复核（不会自动封禁）。";
    }

    // ────────────── /unteach ──────────────

    /** {@code /unteach} 用法。 */
    public static final String UNTEACH_USAGE = "用法：/unteach 规则id（先 /taught_rules 查看本群规则 id）";

    /** 停用成功。 */
    public static String unteachDone(String ruleId) {
        return "规则 " + ruleId + " 停用了（立即生效）。";
    }

    /** 规则不存在。 */
    public static String unteachMissing(String ruleId) {
        return "本群没有规则 " + ruleId + "。";
    }

    // ────────────── /words ──────────────

    /** 词表为空。 */
    public static final String WORDS_EMPTY = "本群还没有违禁词。";

    /** 词表标题。 */
    public static final String WORDS_PREFIX = "本群违禁词：";

    /** 词表截断提示前缀（后接未显示条数）。 */
    public static final String WORDS_TRUNCATED_PREFIX = "、…（另有 ";

    /** 词表截断提示后缀。 */
    public static final String WORDS_TRUNCATED_SUFFIX = " 个词未显示）";

    // ────────────── /rules（教学规则列表）──────────────

    /** 规则列表为空。 */
    public static final String RULES_EMPTY =
            "本群还没有教学规则（可以发 /teach 规则id 正则 描述 添加，例：/teach SCAM_AIRDROP 免费空投 假空投骗局）。";

    /** 规则列表标题。 */
    public static final String RULES_PREFIX = "本群教学规则：";

    /** 取不到群标识时的兜底。 */
    public static final String RULES_NO_CHAT = "无法识别本群。";

    /** 规则行：启用标记。 */
    public static final String RULE_ENABLED_MARK = "✓ ";

    /** 规则行：停用标记。 */
    public static final String RULE_DISABLED_MARK = "✗（已停用）";

    /** 规则列表截断提示（句式与收录库列表共享）。 */
    public static String rulesTruncated(int total, int shown) {
        return com.tg.heyisheng.bot.core.dispatch.DispatchMessages.truncatedNotice(total, shown);
    }
}
