package com.tg.heyisheng.bot.admin.identity;

import com.tg.heyisheng.bot.core.audit.ActorType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话门禁（模块十一 · 账号与权限模型）——取代旧的 {@code AdminAuthFilter}。
 *
 * <p>守的要点：<b>身份只从会话推导</b>。因此「没有会话 / 会话无效 / 只给了裸令牌」一律 401，
 * 且请求头里再没有可伪造的身份字段。登录路径须放行（否则没人拿得到会话）。
 */
class AdminSessionFilterTest {

    private final AdminAuthService auth = mock(AdminAuthService.class);
    private final AdminSessionFilter filter = new AdminSessionFilter(auth);

    @Test
    void missingSessionIsUnauthorized() throws Exception {
        when(auth.authenticate(null)).thenReturn(Optional.empty());

        assertThat(status(request("GET", "/admin/approvals", null))).isEqualTo(401);
    }

    @Test
    void invalidSessionIsUnauthorized() throws Exception {
        when(auth.authenticate("bad")).thenReturn(Optional.empty());

        assertThat(status(request("GET", "/admin/approvals", "Bearer bad"))).isEqualTo(401);
    }

    @Test
    void bareTokenWithoutBearerPrefixIsUnauthorized() throws Exception {
        // 少了 "Bearer " 前缀＝格式解析失败＝没给令牌，而不是「给了但对不上」
        when(auth.authenticate(null)).thenReturn(Optional.empty());

        assertThat(status(request("GET", "/admin/approvals", "rawtoken"))).isEqualTo(401);
    }

    @Test
    void validSessionExposesSubjectAndContinues() throws Exception {
        when(auth.authenticate("good")).thenReturn(Optional.of(
                new AdminAuthService.Authenticated(ActorType.ADMIN_ACCOUNT, 7L, AdminRole.SUPER_ADMIN)));
        MockHttpServletRequest req = request("GET", "/admin/approvals", "Bearer good");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).as("放行时不得改写状态码").isEqualTo(200);
        assertThat(chain.getRequest()).as("链路必须继续往下走").isNotNull();
        assertThat(req.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE)).isEqualTo(ActorType.ADMIN_ACCOUNT);
        assertThat(req.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE)).isEqualTo(7L);
        assertThat(req.getAttribute(AdminSessionFilter.ROLE_ATTRIBUTE)).isEqualTo(AdminRole.SUPER_ADMIN);
    }

    @Test
    void loginPathIsPublic() throws Exception {
        MockHttpServletRequest req = request("POST", "/admin/auth/login", null);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).as("登录路径须放行（否则没人拿得到会话）").isNotNull();
    }

    private static MockHttpServletRequest request(String method, String path, String authorization) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        if (authorization != null) {
            req.addHeader("Authorization", authorization);
        }
        return req;
    }

    private int status(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }
}
