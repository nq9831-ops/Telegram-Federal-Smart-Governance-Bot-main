package com.tg.heyisheng.bot.escrow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 担保交易配置绑定单测：钉住默认值——默认不得指向会静默放行资金的行为。
 *
 * <p>{@code enabled} 默认 false（模块整体不装配）；两个运行期参数有确定默认值，
 * 避免「不设就退化为 0 天争议期 / 0 小时超时」这类隐性放行。
 */
class EscrowPropertiesTest {

    @Test
    void defaultsAreSafe() {
        EscrowProperties properties = new EscrowProperties();

        assertThat(properties.isEnabled())
                .as("默认关闭——不启用则整组组件不装配")
                .isFalse();
        assertThat(properties.getDisputeWindowDays())
                .as("争议期默认 7 天（与 ConfigCatalog / application.yml 口径一致）")
                .isEqualTo(7);
        assertThat(properties.getOrderTimeoutHours())
                .as("未锁仓超时默认 72 小时")
                .isEqualTo(72);
    }

    @Test
    void settersRoundTrip() {
        EscrowProperties properties = new EscrowProperties();
        properties.setEnabled(true);
        properties.setDisputeWindowDays(3);
        properties.setOrderTimeoutHours(12);

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getDisputeWindowDays()).isEqualTo(3);
        assertThat(properties.getOrderTimeoutHours()).isEqualTo(12);
    }
}
