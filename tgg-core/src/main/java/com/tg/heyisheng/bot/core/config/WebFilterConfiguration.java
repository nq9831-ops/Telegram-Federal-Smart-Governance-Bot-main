package com.tg.heyisheng.bot.core.config;

import com.tg.heyisheng.bot.core.web.SecurityHeadersFilter;
import com.tg.heyisheng.bot.core.webhook.SecretTokenFilter;
import com.tg.heyisheng.bot.core.webhook.SecretTokenVerifier;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Web 入口的过滤器装配：webhook secret 校验 + 安全响应头。
 *
 * <p><b>为什么从 {@code TggCoreConfiguration} 拆出来</b>：那一类装配了 29 个 bean、近 500 行，
 * 覆盖命令与权限、审核、通知与保留、教学门槛、web 入口等互不相关的域；本类只依赖
 * {@link WebhookProperties}，是其中依赖最少、最独立的一组。按域拆开后每类可独立阅读，
 * 改动一个域不必通读其余部分。
 *
 * <p><b>拆分不影响装配</b>：{@code @Configuration} 类之间通过方法参数互相注入，
 * Spring 不关心某个 bean 定义在哪个类里——只要它仍在组件扫描范围内。
 */
@Configuration
public class WebFilterConfiguration {

    /** webhook secret 校验（常量时间比对 Telegram 的 {@code X-Telegram-Bot-Api-Secret-Token}）。 */
    @Bean
    public SecretTokenVerifier secretTokenVerifier(WebhookProperties properties) {
        return new SecretTokenVerifier(properties.getSecret());
    }

    /** 把 secret 校验挂到 webhook 路径上。 */
    @Bean
    public FilterRegistrationBean<SecretTokenFilter> secretTokenFilter(
            SecretTokenVerifier verifier, WebhookProperties properties) {
        FilterRegistrationBean<SecretTokenFilter> registration =
                new FilterRegistrationBean<>(new SecretTokenFilter(verifier, properties.getPath()));
        registration.addUrlPatterns(properties.getPath());
        return registration;
    }

    /**
     * 安全响应头（原文 §15.2）。作用于**全部**路径——包括 /webhook、/admin/* 与错误响应。
     *
     * <p>用 {@code HIGHEST_PRECEDENCE} 让它先于 {@link SecretTokenFilter} 执行，
     * 使「401 直接短路、不再走链路」的响应也带上这些头——只在成功响应上加头等于漏掉一半
     * （有 {@code SecurityHeadersIT} 覆盖这条路径）。
     *
     * <p>CSP / HSTS 刻意不在这里：前者约束的是**页面**加载行为，而后台静态站由 nginx / Ingress
     * 托管、不由 Spring 托管；后者必须由 TLS 终止方下发——应用在反代之后以明文 HTTP 收请求，
     * 由它发 HSTS 语义上是错的。
     */
    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> registration =
                new FilterRegistrationBean<>(new SecurityHeadersFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
