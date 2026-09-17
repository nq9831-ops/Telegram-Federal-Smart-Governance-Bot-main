package com.tg.heyisheng.bot.core.moderation;

import java.util.List;

/**
 * 内置的 L1 规则集。
 *
 * <p><b>为什么内置</b>：这是「已知广告模式」的起步集，让 L1 立刻可用。
 * 后续应迁移到数据库以支持按群配置与热更新（V5.0 的 {@code /teach} 会往规则表里加）——
 * 届时本类退化为首次部署时的种子数据。
 *
 * <p><b>正则编写注意</b>：这些模式会逐条跑在每个消息上，需避免灾难性回溯
 * （如嵌套量词 {@code (a+)+}）。当前规则均为简单模式；新增规则时应留意这一点。
 */
public final class BuiltInRules {

    private BuiltInRules() {
    }

    /**
     * 起步规则集。
     *
     * <p>等级划分依据：
     * <ul>
     *   <li>单纯的广告拉人 —— 低风险</li>
     *   <li>与资金、私钥、投资相关的诱导 —— 高风险，其中索要私钥/助记词是硬红线</li>
     * </ul>
     */
    public static List<ModerationRule> all() {
        return List.of(
                // 赌场类广告（V5.0 示例模式）
                ModerationRule.of("SPAM_CASINO", "赌场广告",
                        "(?i)\\d+\\s*casino", RiskLevel.LOW),

                // 幸运号码类广告（V5.0 示例模式）
                ModerationRule.of("SPAM_LUCKY", "幸运号码广告",
                        "(?i)lucky\\s*\\d{4}", RiskLevel.MEDIUM),

                // 投资诱导线：高收益承诺
                ModerationRule.of("SCAM_INVESTMENT", "高收益投资诱导",
                        "(?i)(保本|稳赚|日入\\s*\\d+|passive\\s+income|guaranteed\\s+profit)", RiskLevel.HIGH),

                // 硬红线：索要钱包私钥/助记词——正规服务永不索要，必为诈骗
                ModerationRule.hardLine("HARD_SECRET_PHRASE", "索要助记词/私钥",
                        "(?i)(mnemonic|seed\\s+phrase|private\\s+key|助记词|私钥).{0,20}(发|send|share|给我|dm)"),

                // 硬红线：儿童性剥削相关内容（只做最保守的关键词组合，避免误伤讨论）
                ModerationRule.hardLine("HARD_CSAM", "儿童性剥削",
                        "(?i)(child|minor|underage|未成年).{0,10}(porn|nude|sex|色情|裸)"),

                // 硬红线 R3：儿童诱骗（对未成年人的性诱导 / 索取）——同为保守共现，
                // 需「未成年指向」与「性内容 / 性邀约」同时出现，避免误伤
                // 「未成年人保护法」「儿童性教育」这类正当讨论。
                ModerationRule.hardLine("HARD_GROOMING", "儿童诱骗",
                        "(?i)(未成年|幼女|幼童|小学生|初中生|高中生|\\d{1,2}\\s*岁|child|minor|underage)"
                                + ".{0,15}(裸照|裸体|私密照|私密部位|开房|上床|陪睡|恋童|裸聊|性行为|做爱|nude|naked)"),

                // 硬红线 R4：人口贩卖（招募 / 运送 / 剥削的邀约）——需「贩运话术」与「出境 / 境外务工」
                // 共现，或直接点名已知贩运园区。刻意**不含裸「高薪 + 海外」**：那会误伤正规海外招聘。
                ModerationRule.hardLine("HARD_TRAFFICKING", "人口贩卖",
                        "(?i)(缅北|妙瓦底|kk园区|杀猪盘|嘎腰子|割腰子)"
                                + "|((包吃住|包机票|包签证|包接送|零经验|无需经验|不要学历)"
                                + ".{0,12}(出境|出国|境外务工|缅北|柬埔寨|东南亚|缅甸))")
        );
    }
}
