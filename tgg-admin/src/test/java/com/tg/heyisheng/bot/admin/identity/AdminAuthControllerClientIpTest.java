package com.tg.heyisheng.bot.admin.identity;

import com.tg.heyisheng.bot.admin.AdminProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录限流键的来源 IP 取值（模块十一 · 管理面登录加固）。
 *
 * <p>守的重点：<b>X-Forwarded-For 的<b>首段</b>完全由客户端控制</b>——若直接拿它当限流键，
 * 轮换该头即可绕过「按来源 IP 的登录限流」，使「防跨账号爆破」静默失效。
 * 故默认取 {@code getRemoteAddr()}；仅在显式信任反代（{@code tgg.admin.trust-forwarded-for=true}）
 * 时取 XFF 的<b>最后一跳</b>（由直连的反代追加，客户端无法移除）。
 */
class AdminAuthControllerClientIpTest {

    @SuppressWarnings("unchecked")
    private static AdminAuthController controller(boolean trustForwardedFor) {
        AdminProperties props = new AdminProperties();
        props.setTrustForwardedFor(trustForwardedFor);
        // 仅 clientIp 用到 properties；其余依赖传 mock（ObjectProvider.getIfAvailable() 默认返回 null）。
        // 末参 accountAdmin 供 TOTP 自助端点使用，本测试不触及故传 null。
        return new AdminAuthController(null, props,
                mock(ObjectProvider.class), mock(ObjectProvider.class), null,
                mock(ObjectProvider.class), null, null);
    }

    private static HttpServletRequest request(String xff, String remoteAddr) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn(xff);
        when(req.getRemoteAddr()).thenReturn(remoteAddr);
        return req;
    }

    @Test
    void defaultUsesRemoteAddrNotClientControllableHeader() {
        AdminAuthController controller = controller(false);

        assertThat(controller.clientIp(request("6.6.6.6", "9.9.9.9")))
                .as("默认不得采信可伪造的 XFF 首段")
                .isEqualTo("9.9.9.9");
    }

    @Test
    void whenTrustingProxyTakesLastHopNotFirstSegment() {
        AdminAuthController controller = controller(true);

        assertThat(controller.clientIp(request("6.6.6.6, 10.0.0.1", "10.0.0.1")))
                .as("信任反代时取最后一跳（客户端无法移除），而非客户端可控的首段")
                .isEqualTo("10.0.0.1");
    }
}
