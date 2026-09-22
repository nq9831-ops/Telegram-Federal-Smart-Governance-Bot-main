package com.tg.heyisheng.bot.listing.merchant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商家配置解析单测。
 *
 * <p>原 {@code tgg.merchant.reviewers} 的三条解析用例随**商家复核通道裁撤**退场
 * （2026-09-22 二次拍板：「商家只有联邦管理员才可以审核处理」，授权源改为
 * {@code tgg.federation.admins} / 平台 {@code FEDERATION_ADMIN}）。
 */
class MerchantPropertiesTest {

    @Test
    void defaultsMatchV5Spec() {
        MerchantProperties properties = new MerchantProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getInitialScore()).as("V5.0 §7.1：商家初始信用分 500").isEqualTo(500);
    }
}
