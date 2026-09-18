package com.tg.heyisheng.bot.admin;

import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import com.tg.heyisheng.bot.core.webhook.SecretTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审批中心门禁（模块十一）。
 *
 * <p>两层各自的失败模式不同，故分开覆盖：token 层管「够不够得着后台」（401），
 * operator 白名单层管「有没有资格审批」（403）。混在一起会导致「token 对了但白名单漏判」
 * 这种越权被 401 的测试掩盖过去。
 */
class AdminAuthFilterTest {

    private static final String TOKEN = "admin-token";
    private static final long REVIEWER = 777L;
    private static final long OUTSIDER = 999L;

    private final AdminAuthFilter filter = new AdminAuthFilter(
            new SecretTokenVerifier(TOKEN),
            new ModerationReviewGuard(String.valueOf(REVIEWER)));

    private static MockHttpServletRequest request(String authorization, String operatorId) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin/approvals");
        if (authorization != null) {
            req.addHeader("Authorization", authorization);
        }
        if (operatorId != null) {
            req.addHeader("X-Operator-Id", operatorId);
        }
        return req;
    }

    private int statusAfter(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        assertThat(statusAfter(request(null, String.valueOf(REVIEWER)))).isEqualTo(401);
    }

    @Test
    void wrongTokenIsUnauthorized() throws Exception {
        assertThat(statusAfter(request("Bearer nope", String.valueOf(REVIEWER)))).isEqualTo(401);
    }

    @Test
    void bareTokenWithoutBearerPrefixIsUnauthorized() throws Exception {
        // 少了 "Bearer " 前缀不算通过——格式解析失败必须等价于「没给令牌」，而不是「给了但对不上」
        assertThat(statusAfter(request(TOKEN, String.valueOf(REVIEWER)))).isEqualTo(401);
    }

    @Test
    void validTokenWithoutOperatorHeaderIsForbidden() throws Exception {
        assertThat(statusAfter(request("Bearer " + TOKEN, null))).isEqualTo(403);
    }

    @Test
    void nonWhitelistedOperatorIsForbiddenEvenWithAValidToken() throws Exception {
        assertThat(statusAfter(request("Bearer " + TOKEN, String.valueOf(OUTSIDER)))).isEqualTo(403);
    }

    @Test
    void nonNumericOperatorIsForbidden() throws Exception {
        assertThat(statusAfter(request("Bearer " + TOKEN, "not-a-number"))).isEqualTo(403);
    }

    @Test
    void validTokenAndWhitelistedOperatorPassesAndExposesTheOperator() throws Exception {
        MockHttpServletRequest req = request("Bearer " + TOKEN, String.valueOf(REVIEWER));
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).as("放行时不得改写状态码").isEqualTo(200);
        assertThat(chain.getRequest()).as("链路必须继续往下走").isNotNull();
        assertThat(req.getAttribute(AdminAuthFilter.OPERATOR_ATTRIBUTE))
                .as("operator 要传给控制器，避免它在别处再解析一遍")
                .isEqualTo(REVIEWER);
    }
}
