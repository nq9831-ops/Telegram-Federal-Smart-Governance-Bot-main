package com.tg.heyisheng.bot.admin.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * 后台会话门禁（模块十一 · 账号与权限模型）——<b>取代</b>旧的 {@code AdminAuthFilter}。
 *
 * <p><b>为什么换掉旧的</b>：旧实现把身份来源定为请求头 {@code X-Operator-Id}（一个裸数字），
 * 它<b>可被任意伪造</b>——任何拿到共享 token 的人都能自称复核人 777。新实现<b>只从服务端会话推导身份</b>：
 * {@code Authorization: Bearer <会话令牌>} → 反查 {@code admin_sessions} → 得到 (主体类型, 主体 id)。
 * 请求头不再携带任何身份信息。
 *
 * <p><b>门禁只有一处</b>：本过滤器统一验明身份并写入请求属性；控制器只取用、不重复判定——
 * 避免出现「某个端点漏判」这类最典型的越权。
 *
 * <p><b>放行名单</b>：{@code /admin/auth/login} 与 {@code /admin/auth/telegram}（登录本身无需会话）；
 * 其余 {@code /admin/*} 一律要求有效会话。
 */
public class AdminSessionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminSessionFilter.class);

    /** 通过门禁后写入的请求属性：主体类型（{@link com.tg.heyisheng.bot.core.audit.ActorType}）。 */
    public static final String SUBJECT_TYPE_ATTRIBUTE = "tgg.admin.subjectType";
    /** 主体 id（账号 id 或 TG userId）。 */
    public static final String SUBJECT_ID_ATTRIBUTE = "tgg.admin.subjectId";
    /** 角色（{@link AdminRole}；TG 用户为 null）。 */
    public static final String ROLE_ATTRIBUTE = "tgg.admin.role";

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /** 无需会话的路径（登录入口 + 登录页配置）。 */
    private static final Set<String> PUBLIC_PATHS =
            Set.of("/admin/auth/login", "/admin/auth/telegram", "/admin/auth/login-config");

    private final AdminAuthService auth;

    public AdminSessionFilter(AdminAuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (PUBLIC_PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        Optional<AdminAuthService.Authenticated> subject =
                auth.authenticate(bearer(request.getHeader(AUTHORIZATION_HEADER)));
        if (subject.isEmpty()) {
            log.warn("后台拒绝：会话无效或缺失（path={}）", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        AdminAuthService.Authenticated s = subject.get();
        request.setAttribute(SUBJECT_TYPE_ATTRIBUTE, s.subjectType());
        request.setAttribute(SUBJECT_ID_ATTRIBUTE, s.subjectId());
        request.setAttribute(ROLE_ATTRIBUTE, s.role());
        chain.doFilter(request, response);
    }

    /** 从 {@code Authorization: Bearer xxx} 取令牌；格式不符返回 null。 */
    static String bearer(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }
}
