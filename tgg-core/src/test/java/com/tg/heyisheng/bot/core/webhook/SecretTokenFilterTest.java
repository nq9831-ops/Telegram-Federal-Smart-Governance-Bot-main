package com.tg.heyisheng.bot.core.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecretTokenFilter 测试（不需要 Spring 上下文，直接驱动 Filter）。
 *
 * <p>这是本项目自实现的校验——TelegramBots 库<b>不</b>校验 secret
 * （见设计文档 §5.6：库中 {@code secretToken} 仅作为 SetWebhook 的出参存在）。
 */
class SecretTokenFilterTest {

    private static final String SECRET = "s3cr3t-token-value";
    private static final String BOT_PATH = "/webhook";

    private final SecretTokenFilter filter =
            new SecretTokenFilter(new SecretTokenVerifier(SECRET), BOT_PATH);

    @Test
    void rejectsRequestWithoutSecretHeader() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(post("/webhook"), res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).as("被拒请求不得继续流转").isNull();
    }

    @Test
    void rejectsRequestWithWrongSecret() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletRequest req = post("/webhook");
        req.addHeader(SecretTokenFilter.SECRET_HEADER, "wrong-value");
        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void passesRequestWithCorrectSecret() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletRequest req = post("/webhook");
        req.addHeader(SecretTokenFilter.SECRET_HEADER, SECRET);
        filter.doFilter(req, res, chain);

        assertThat(chain.getRequest()).as("合法请求必须继续流转").isNotNull();
    }

    @Test
    void ignoresUnrelatedPaths() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(post("/actuator/health"), res, chain);

        assertThat(chain.getRequest()).as("非 bot 路径不应被拦截").isNotNull();
    }

    private static MockHttpServletRequest post(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }
}
