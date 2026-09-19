package com.tg.heyisheng.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 文档**启用**时的行为（`TGG_OPENAPI_ENABLED=true`）。
 *
 * <p>用完整 Spring 上下文 + MockMvc，**不 mock 中间层**——要验证的正是
 * 「springdoc 真的扫到了控制器」这件事，mock 掉就什么都验不到。
 *
 * <p>同时设 {@code tgg.admin.api-token}：{@code ApprovalController} 带
 * {@code @Conditional(AdminApiTokenCondition.class)}，token 为空时**整个控制器不装配**，
 * 文档里自然也不会有它——那条断言就失去意义了。
 */
@SpringBootTest(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
        "tgg.admin.api-token=it-openapi-token"
})
@AutoConfigureMockMvc
class OpenApiDocsEnabledIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void exposesApiDocsAndCoversApprovalEndpoints() throws Exception {
        String body = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // OpenAPI 文档的特征字段
        assertThat(body).contains("openapi");
        // 真扫到了审批中心（模块十一）的端点，而不是只生成一份空壳文档
        assertThat(body).contains("/admin/approvals");
        // 裁决端点（POST /admin/approvals/{id}/decide）的路径模板
        assertThat(body).contains("/admin/approvals/{id}/decide");
    }
}
