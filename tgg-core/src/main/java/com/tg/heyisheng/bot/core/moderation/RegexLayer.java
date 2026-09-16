package com.tg.heyisheng.bot.core.moderation;

import java.util.List;
import java.util.Optional;

/**
 * L1 正则层：用预置规则集快速匹配已知的违规模式。
 *
 * <p><b>定位</b>：四层里最便宜的一层。它只能识别「已经被写进规则」的模式，
 * 因此对变体、隐晦表达无能为力——那是 L2/L3/L4 的职责。
 * 它的价值在于把大量显而易见的垃圾挡在最前面，省下后续层的算力与外部调用。
 *
 * <p><b>规则来源</b>：构造时注入。当前由代码内置，后续应支持从数据库
 * 按群加载与热更新（V5.0 的 {@code /teach} 要往这里加规则）。
 * 因此本类<b>不做规则持久化</b>，只做匹配——保持职责单一。
 *
 * <p><b>性能约束</b>：本层在每个消息上都会跑一遍全部规则，
 * 故规则数增长时需关注匹配开销（正则回溯风险由规则编写方负责，见规则表注释）。
 */
public class RegexLayer implements ModerationLayer {

    private final List<ModerationRule> rules;

    public RegexLayer(List<ModerationRule> rules) {
        this.rules = List.copyOf(rules == null ? List.of() : rules);
    }

    @Override
    public Optional<LayerHit> inspect(String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }

        LayerHit worst = null;
        for (ModerationRule rule : rules) {
            if (!rule.matches(text)) {
                continue;
            }
            LayerHit hit = new LayerHit(rule.id(), rule.riskLevel(), rule.hardLine());
            // 硬红线一旦命中立即返回：它的处置是「不等复核」，没必要继续找更严重的
            if (rule.hardLine()) {
                return Optional.of(hit);
            }
            if (worst == null || hit.riskLevel().severity() > worst.riskLevel().severity()) {
                worst = hit;
            }
        }
        return Optional.ofNullable(worst);
    }

    @Override
    public String name() {
        return "L1-regex";
    }

    /** 已装载的规则数（便于诊断与测试）。 */
    public int ruleCount() {
        return rules.size();
    }
}
