package com.tg.heyisheng.bot.admin.identity;

import com.tg.heyisheng.bot.admin.AdminApiTokenCondition;
import com.tg.heyisheng.bot.admin.AdminProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 后台认证端点（模块十一 · 账号与权限模型）。
 *
 * <pre>
 * POST /admin/auth/login   账号 + 密码 → 签发会话（body: username + password）
 * POST /admin/auth/logout  吊销当前会话（需 Bearer 会话令牌）
 * GET  /admin/auth/me      返回当前登录主体（需 Bearer 会话令牌）
 * </pre>
 *
 * <p><b>状态码用 {@code ResponseEntity} 显式返回</b>：本项目全局异常处理器把业务异常吞成 200，
 * 抛异常表达 401 会得到 200。故一律显式返回状态码。
 *
 * <p><b>login 不泄露失败成因</b>：用户名不存在 / 停用 / 锁定 / 密码错都是同一句 401——
 * 分别返回会给攻击者枚举信息。
 */
@RestController
@Conditional(AdminApiTokenCondition.class)
@RequestMapping(path = "/admin/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminAuthController {

    private final AdminAuthService auth;
    private final AdminProperties properties;

    public AdminAuthController(AdminAuthService auth, AdminProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    /** 账号 + 密码登录。 */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(@RequestBody LoginRequest body) {
        Optional<AdminAuthService.LoginResult> result = auth.login(
                body.username(), body.password(), Duration.ofHours(properties.getSessionTtlHours()));
        if (result.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "登录名或密码错误"));
        }
        AdminAuthService.LoginResult r = result.get();
        return ResponseEntity.ok(new LoginResponse(
                r.token(), r.subject().subjectType().name(), r.subject().subjectId(),
                r.subject().role() == null ? null : r.subject().role().name()));
    }

    /** 登出（吊销当前会话）。 */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        auth.logout(AdminSessionFilter.bearer(request.getHeader("Authorization")));
        return ResponseEntity.ok(Map.of("result", "OK"));
    }

    /** 当前登录主体（前端据以渲染身份行；filter 已验会话）。 */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(HttpServletRequest request) {
        Object role = request.getAttribute(AdminSessionFilter.ROLE_ATTRIBUTE);
        return ResponseEntity.ok(new MeResponse(
                String.valueOf(request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE)),
                (Long) request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE),
                role == null ? null : role.toString()));
    }

    /** 登录请求体。 */
    public record LoginRequest(String username, String password) {
    }

    /** 登录成功响应：明文令牌（仅此一次）。 */
    public record LoginResponse(String token, String subjectType, Long subjectId, String role) {
    }

    /** 当前主体。 */
    public record MeResponse(String subjectType, Long subjectId, String role) {
    }
}
