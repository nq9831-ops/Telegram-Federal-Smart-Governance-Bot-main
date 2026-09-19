package com.tg.heyisheng.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 安全响应头（原文 §15.2）确实挂在**所有**响应上，而不只是成功的那一半。
 *
 * <p>为什么把「401 短路」单列一条：安全头最容易的漏法是「只在正常返回时补」——
 * 而 {@code SecretTokenFilter} 在不符 secret 时直接 401 并中断链路，
 * 异常/拒绝路径同样是对外可见的响应。只测成功路径等于漏掉一半命题。
 *
 * <p>CSP 与 HSTS **不在这里测**——它们刻意不由应用下发（HSTS 必须来自 TLS 终止方，
 * CSP 归托管静态站的反代），理由见 {@code SecurityHeadersFilter} 的 javadoc。
 * 它们落地在部署文档与 nginx 示例中。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityHeadersIT {

    @Autowired
    private MockMvc mvc;

    private static void assertSecurityHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void successResponsesCarrySecurityHeaders() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/actuator/health")).andReturn().getResponse();

        assertThat(response.getStatus()).as("前置条件：探针端点本身可达").isEqualTo(200);
        assertSecurityHeaders(response);
    }

    @Test
    void shortCircuitedRejectionsAlsoCarrySecurityHeaders() throws Exception {
        // /webhook 不带 secret 头 → SecretTokenFilter 直接 401 并中断，不进入后续链路。
        // 这正是「只在成功响应上加头」会漏掉的路径。
        MockHttpServletResponse response = mvc.perform(post("/webhook")).andReturn().getResponse();

        assertThat(response.getStatus())
                .as("前置条件：无 secret 的 webhook 必须被 401 短路，否则本用例没测到目标路径")
                .isEqualTo(401);
        assertSecurityHeaders(response);
    }
}
