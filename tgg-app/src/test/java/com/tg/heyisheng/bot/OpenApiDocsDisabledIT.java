package com.tg.heyisheng.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * OpenAPI 文档**默认关闭**时的行为（不设 {@code TGG_OPENAPI_ENABLED}）。
 *
 * <p>为什么这条断言重要：springdoc 自身的默认值是 {@code enabled=true}——若不显式关掉，
 * 引入依赖这一动作就等于把全部端点清单与参数结构暴露出去。项目纪律是「未启用即零影响」，
 * 故本测试锁住「**默认**不暴露」这一契约。
 *
 * <p><b>不硬断言状态码</b>：本项目有一个全局 {@code @RestControllerAdvice} 把异常统一吞成
 * 200（为 Telegram 避免重试风暴的既有设计），未知路径因此可能是 200 而非 404。
 * 断言「响应体里没有文档特征」比断言状态码更贴近要证明的命题。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsDisabledIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void doesNotExposeApiDocsUnlessExplicitlyEnabled() throws Exception {
        MvcResult result = mvc.perform(get("/v3/api-docs")).andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(body).doesNotContain("openapi");
        assertThat(body).doesNotContain("/admin/approvals");
    }

    @Test
    void doesNotExposeSwaggerUiUnlessExplicitlyEnabled() throws Exception {
        MvcResult result = mvc.perform(get("/swagger-ui/index.html")).andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(body).doesNotContain("swagger-ui");
        assertThat(body).doesNotContain("Swagger UI");
    }
}
