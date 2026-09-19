package com.tg.heyisheng.bot.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 给**所有**响应补一组安全响应头（原文 §15.2「应用：OWASP ASVS、Actuator 锁定、CSP/HSTS」）。
 *
 * <p><b>为什么这三条在应用层做，而 CSP / HSTS 不在这里</b>：
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff}、{@code X-Frame-Options: DENY}、
 *       {@code Referrer-Policy: no-referrer} —— 对**本站响应**生效，与谁在前面终止 TLS 无关，
 *       放在应用层是正确的位置；而且它们同时覆盖 API 与错误响应。</li>
 *   <li>{@code Strict-Transport-Security} 必须由 **TLS 终止方**（反向代理 / Ingress）下发。
 *       应用若在反代之后仍以明文 HTTP 收请求，由它发 HSTS 在语义上是错的
 *       （应用看到的 scheme 并非 https，也不该替反代承诺升级）。</li>
 *   <li>{@code Content-Security-Policy} 主要约束**页面**的加载行为，而本项目的后台静态站
 *       由 nginx / Ingress 托管、**不由 Spring 托管**（见 {@code frontend/README.md}），
 *       应用加 CSP 对它毫无作用。故 CSP 同样归反代层。</li>
 * </ul>
 * 因此本类刻意**只**加前三条；后两条的落地位置写进部署文档与 nginx 示例。
 *
 * <p><b>注册顺序</b>：由 {@code TggCoreConfiguration} 以 {@code HIGHEST_PRECEDENCE} 注册，
 * 让 401（{@code SecretTokenFilter} 直接短路、不再走链路）这类**异常路径**也带上这些头——
 * 只在成功响应上加头等于漏掉一半。
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String NOSNIFF = "X-Content-Type-Options";
    static final String FRAME_OPTIONS = "X-Frame-Options";
    static final String REFERRER_POLICY = "Referrer-Policy";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 先设头再放行：后续任何短路（401）或异常处理都发生在 doFilter 之内，
        // 头已挂在 response 上，不会被丢掉。
        response.setHeader(NOSNIFF, "nosniff");
        response.setHeader(FRAME_OPTIONS, "DENY");
        response.setHeader(REFERRER_POLICY, "no-referrer");
        filterChain.doFilter(request, response);
    }
}
