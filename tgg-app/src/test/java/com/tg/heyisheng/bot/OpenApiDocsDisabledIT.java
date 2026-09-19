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
 * <p><b>为什么断言响应体而非状态码</b>：要证明的命题是「默认不暴露文档」（响应体里没有文档特征），
 * 而不是「某条路径返回某个状态码」——状态码会随路由细节变动。
 * 断言响应体比断言状态码更贴近命题、也更稳固。
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
