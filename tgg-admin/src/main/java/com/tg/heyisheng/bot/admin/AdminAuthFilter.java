package com.tg.heyisheng.bot.admin;

import com.tg.heyisheng.bot.core.moderation.ModerationReviewGuard;
import com.tg.heyisheng.bot.core.webhook.SecretTokenVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 审批中心的双层门禁（模块十一）。
 *
 * <p><b>为什么要两层</b>：单靠一个共享 token，任何拿到它的人都成了「复核人」；
 * 单靠 operator 头，则头可被任意伪造。两者叠加才是「<b>token 证明你够得着这个后台</b>，
 * <b>白名单证明你有权审批</b>」——粗粒度与细粒度各管一段。
 *
 * <p><b>常量时间比对</b>：token 校验复用 {@link SecretTokenVerifier}（{@code MessageDigest.isEqual}），
 * 与 webhook 的 secret 校验同一实现，避免按字节短路比较泄露前缀（V5.0 明确要求）。
 *
 * <p><b>operator 从请求头来、而不是从 token 映射</b>：token 是共享密钥、不指向具体的人；
 * 而审计与「不可自审」都需要真实 userId，故由 {@code X-Operator-Id} 携带并**强制落在复核人白名单内**
 * （与 {@code /review_*} 同一套白名单，避免出现第二套「谁能审批」的定义）。
 *
 * <p><b>不打印 token</b>：拒绝日志只记请求路径，绝不回显凭据。
 */
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    /** 通过门禁后写入的请求属性名：操作者 userId，供控制器直接取用。 */
    public static final String OPERATOR_ATTRIBUTE = "tgg.admin.operatorId";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String OPERATOR_HEADER = "X-Operator-Id";

    private final SecretTokenVerifier tokenVerifier;
    private final ModerationReviewGuard guard;

    public AdminAuthFilter(SecretTokenVerifier tokenVerifier, ModerationReviewGuard guard) {
        this.tokenVerifier = tokenVerifier;
        this.guard = guard;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!tokenVerifier.verify(bearer(request.getHeader(AUTHORIZATION_HEADER)))) {
            log.warn("审批中心拒绝：token 不符（path={}）", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        Long operator = parseOperator(request.getHeader(OPERATOR_HEADER));
        if (operator == null || !guard.isReviewer(operator)) {
            log.warn("审批中心拒绝：operator 缺失或不在复核人白名单（path={}）", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        request.setAttribute(OPERATOR_ATTRIBUTE, operator);
        chain.doFilter(request, response);
    }

    /** 从 {@code Authorization: Bearer xxx} 取出令牌；格式不符一律返回 null（交由校验器判假）。 */
    private static String bearer(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    private static Long parseOperator(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
