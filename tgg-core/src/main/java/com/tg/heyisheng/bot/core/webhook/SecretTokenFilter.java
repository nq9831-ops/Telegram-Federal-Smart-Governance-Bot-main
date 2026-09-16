package com.tg.heyisheng.bot.core.webhook;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 校验 Telegram webhook 请求的 secret token 头。
 *
 * <p>挂在库提供的 {@code @RestController}（映射 {@code POST /{botPath}}）之前：
 * 校验失败直接 401 并中断链路，不进入业务处理。
 *
 * <p>刻意不打印任何请求头内容——避免 secret 进入日志。
 */
public class SecretTokenFilter extends OncePerRequestFilter {

    /** Telegram 在设置了 secret_token 后，会在每个 webhook 请求中带上此头。 */
    public static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final SecretTokenVerifier verifier;
    private final String botPath;

    public SecretTokenFilter(SecretTokenVerifier verifier, String botPath) {
        this.verifier = verifier;
        this.botPath = botPath;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !botPath.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!verifier.verify(request.getHeader(SECRET_HEADER))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
