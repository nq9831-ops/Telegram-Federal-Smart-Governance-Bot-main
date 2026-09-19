package com.tg.heyisheng.bot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Actuator 的**锁定**行为：health 可达，其余端点不对外提供内容（原文 §15.2「Actuator 锁定」）。
 *
 * <p>为什么值得单列一条 IT：Actuator 是「引入即扩大暴露面」的组件——它的默认风险不是功能坏了，
 * 而是有人顺手把 {@code exposure.include} 改成 {@code "*"}（或加上 env / beans / configprops），
 * 于是一张含配置值、环境变量与 bean 依赖图的内部结构被挂到一个**未鉴权**的路径上，
 * 而这一切**不会有任何报错**。本测试把「只有 health」钉死。
 *
 * <p><b>为什么断言响应体而非状态码</b>：要证明的命题是「这些端点没有对外提供内容」——断言**响应体
 * 不含其特征**最贴近它，也最稳固。状态码会随路由细节变动（未暴露的 {@code /actuator/env} 可能落到
 * 静态资源处理器而给 404，见 {@code UnknownPathIT}），拿它当判据会把「路由变了」误读成「暴露了」。
 *
 * <p><b>对照组</b>：{@link #healthIsExposedAndReportsUp()} 在**同一上下文**里证明 MockMvc 确实
 * 能取到 Actuator 的内容——否则「env 端点没内容」也可能只是测试装置坏了（恒真断言）。
 *
 * <p>⚠️ <b>本 IT 跑的是测试配置</b>（{@code src/test/resources/application.yml} 会遮蔽生产 yml），
 * 故它证明的是「默认锁定」；**生产** yml 里那三行显式值由 {@code ConfigurationMappingTest} 守。
 * 两层缺一不可。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorLockedIT {

    @Autowired
    private MockMvc mvc;

    private String bodyOf(String path) throws Exception {
        return mvc.perform(get(path)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void healthIsExposedAndReportsUp() throws Exception {
        String body = bodyOf("/actuator/health");

        assertThat(body)
                .as("health 必须可达且报 UP——它正是引入 Actuator 的目的（真实业务就绪）")
                .contains("UP");
    }

    @Test
    void healthDoesNotLeakComponentDetails() throws Exception {
        String body = bodyOf("/actuator/health");

        // show-details=never：只回整体状态，不回分项
        assertThat(body).doesNotContain("components");
        assertThat(body).doesNotContain("diskSpace");
        assertThat(body).doesNotContain("dataSource");
    }

    @Test
    void envEndpointIsNotExposed() throws Exception {
        String body = bodyOf("/actuator/env");

        assertThat(body).doesNotContain("propertySources");
        assertThat(body).doesNotContain("systemProperties");
    }

    @Test
    void beansEndpointIsNotExposed() throws Exception {
        assertThat(bodyOf("/actuator/beans")).doesNotContain("contexts");
    }

    @Test
    void configPropsEndpointIsNotExposed() throws Exception {
        String body = bodyOf("/actuator/configprops");

        assertThat(body).doesNotContain("contexts");
        assertThat(body).as("配置键一旦泄漏即等于内部结构图").doesNotContain("tgg.webhook");
    }
}
