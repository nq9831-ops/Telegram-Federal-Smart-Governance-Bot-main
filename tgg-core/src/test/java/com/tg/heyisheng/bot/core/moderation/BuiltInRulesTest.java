package com.tg.heyisheng.bot.core.moderation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置规则集测试（模块九 §10.1 红线四类）。
 *
 * <p><b>本类守的是「红线不许误伤」</b>：红线命中即<b>删除 + 封禁</b>，误判的代价是封掉真人
 * ——虽然操作员可经 `/review_reject` 解封，但正确的做法是让模式本身够保守。
 * 故每个红线都配一条「正当讨论 / 正规招聘」的反向断言。
 */
class BuiltInRulesTest {

    private final RegexLayer layer = new RegexLayer(BuiltInRules.all());

    private boolean hits(String text) {
        return layer.inspect(text).isPresent();
    }

    private boolean hitsHard(String ruleId, String text) {
        return layer.inspect(text)
                .filter(hit -> hit.ruleId().equals(ruleId) && hit.hardLine())
                .isPresent();
    }

    @Test
    void allHardLineRulesAreMarkedHardLine() {
        assertThat(BuiltInRules.all())
                .filteredOn(rule -> rule.id().startsWith("HARD_"))
                .as("所有 HARD_* 规则都必须是红线（hardLine=true）")
                .isNotEmpty()
                .allMatch(ModerationRule::hardLine);
        assertThat(BuiltInRules.all()).extracting(ModerationRule::id)
                .contains("HARD_SECRET_PHRASE", "HARD_CSAM", "HARD_GROOMING", "HARD_TRAFFICKING");
    }

    @Test
    void flagsChildGrooming() {
        // 只命中 HARD_GROOMING 的样例（不含 CSAM 的 未成年/child/minor 词，故不会被 HARD_CSAM 先截胡）
        assertThat(hitsHard("HARD_GROOMING", "14岁 开房")).isTrue();
        assertThat(hitsHard("HARD_GROOMING", "找12 岁女生陪睡")).isTrue();
        // 含「未成年 + 裸照」的样例由同族红线（HARD_CSAM）先命中——只要仍被判为硬红线即正确
        assertThat(layer.inspect("找未成年女生，发裸照给我").orElseThrow().hardLine())
                .as("同族红线先命中亦可，但必须是硬红线").isTrue();
    }

    @Test
    void doesNotFlagLegitimateChildSafetyDiscussion() {
        assertThat(hits("未成年人保护法宣传：如何预防儿童性侵害"))
                .as("儿童保护类讨论不得被误伤").isFalse();
        assertThat(hits("本群禁止未成年人入内")).isFalse();
        assertThat(hits("学校组织初中生参观科技馆")).isFalse();
    }

    @Test
    void flagsHumanTrafficking() {
        assertThat(hitsHard("HARD_TRAFFICKING", "包吃住包机票，月入三万，出境务工")).isTrue();
        assertThat(hitsHard("HARD_TRAFFICKING", "缅北科技园大量招人")).isTrue();
    }

    @Test
    void doesNotFlagLegitimateRecruitment() {
        assertThat(hits("包吃住，工作地点在东莞工厂，无需经验"))
                .as("境内正常招聘不得被误伤").isFalse();
        assertThat(hits("海外高薪岗位，正规工作签证"))
                .as("裸「高薪 + 海外」不得进红线——那会误伤正规海外招聘").isFalse();
    }
}
