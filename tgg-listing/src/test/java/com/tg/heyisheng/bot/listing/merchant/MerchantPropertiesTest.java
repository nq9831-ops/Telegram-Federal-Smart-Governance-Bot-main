package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.common.exception.TggConfigException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商家配置解析单测（{@code tgg.merchant.reviewers} 的解析口径）。
 *
 * <p><b>非数字条目必须启动期抛</b>：配置错误若被静默忽略，表现为「配了复核人但没人能审」
 * ——本项目反复踩过的「空配置即静默失效」坑。
 */
class MerchantPropertiesTest {

    @Test
    void defaultsMatchV5Spec() {
        MerchantProperties properties = new MerchantProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getInitialScore()).as("V5.0 §7.1：商家初始信用分 500").isEqualTo(500);
    }

    @Test
    void blankSpecYieldsEmptyList() {
        MerchantProperties properties = new MerchantProperties();
        properties.setReviewers("   ");

        assertThat(properties.parsedReviewers()).isEmpty();
    }

    @Test
    void parsesCommaSeparatedIdsIgnoringBlanks() {
        MerchantProperties properties = new MerchantProperties();
        properties.setReviewers(" 42 , 43 ,, 44 ");

        assertThat(properties.parsedReviewers()).containsExactly(42L, 43L, 44L);
    }

    @Test
    void nonNumericEntryFailsFast() {
        MerchantProperties properties = new MerchantProperties();
        properties.setReviewers("42,abc");

        assertThatThrownBy(properties::parsedReviewers)
                .as("配置错误应在装配期暴露，而不是运行期静默漏掉一个复核人")
                .isInstanceOf(TggConfigException.class)
                .hasMessageContaining("abc");
    }
}
