package com.tg.heyisheng.bot.admin.identity;

import com.tg.heyisheng.bot.admin.AdminApiTokenCondition;
import com.tg.heyisheng.bot.admin.AdminProperties;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.platform.PlatformGrantSource;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;
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
import java.util.Arrays;
import java.util.List;
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
    private final TelegramLoginVerifier telegramVerifier;
    private final AdminLoginRateLimiter rateLimiter;
    private final PlatformGrantSource grants;

    public AdminAuthController(AdminAuthService auth, AdminProperties properties,
                               org.springframework.beans.factory.ObjectProvider<TelegramLoginVerifier> verifier,
                               org.springframework.beans.factory.ObjectProvider<AdminLoginRateLimiter> rateLimiter,
                               PlatformGrantSource grants) {
        this.auth = auth;
        this.properties = properties;
        this.telegramVerifier = verifier.getIfAvailable();
        this.rateLimiter = rateLimiter.getIfAvailable();
        this.grants = grants;
    }

    /**
     * Telegram Login Widget 回调登录。
     *
     * <p>前端传来的字段一律不可信——服务端用 bot token 重算 HMAC 验签，通过才签发会话。
     * 未配置 bot token 时本端点返回 503（功能未启用，而非验签失败）。
     */
    @PostMapping(path = "/telegram", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> telegram(@RequestBody java.util.Map<String, String> data) {
        if (telegramVerifier == null || !telegramVerifier.isConfigured()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "未配置 bot token，Telegram 登录不可用"));
        }
        java.util.Optional<TelegramLoginVerifier.TelegramUser> user =
                telegramVerifier.verify(data, java.time.Instant.now());
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Telegram 登录校验失败"));
        }
        AdminAuthService.LoginResult r = auth.loginAsTelegramUser(
                user.get().userId(), Duration.ofHours(properties.getSessionTtlHours()));
        return ResponseEntity.ok(new LoginResponse(r.token(), r.subject().subjectType().name(),
                r.subject().subjectId(), null, permissionsOfSubject(r.subject())));
    }

    /** 账号 + 密码登录。 */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        // 按来源 IP 限流（补账号锁定之外的「跨账号爆破」这一维）
        if (rateLimiter != null && !rateLimiter.tryAcquire(clientIp(request))) {
            return ResponseEntity.status(429).body(Map.of("error", "登录尝试过于频繁，请稍后再试"));
        }
        Optional<AdminAuthService.LoginResult> result = auth.login(
                body.username(), body.password(), body.totpCode(),
                Duration.ofHours(properties.getSessionTtlHours()));
        if (result.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "登录名或密码错误"));
        }
        AdminAuthService.LoginResult r = result.get();
        return ResponseEntity.ok(new LoginResponse(
                r.token(), r.subject().subjectType().name(), r.subject().subjectId(),
                r.subject().role() == null ? null : r.subject().role().name(),
                permissionsOfSubject(r.subject())));
    }

    /** 登出（吊销当前会话）。 */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        auth.logout(AdminSessionFilter.bearer(request.getHeader("Authorization")));
        return ResponseEntity.ok(Map.of("result", "OK"));
    }

    /** 公开：登录页需要的非敏感配置（Telegram bot username）。无需会话。 */
    @GetMapping("/login-config")
    public ResponseEntity<Map<String, String>> loginConfig() {
        String username = properties.getTgLoginBotUsername();
        return ResponseEntity.ok(Map.of("telegramBotUsername", username == null ? "" : username));
    }

    /** 当前登录主体（前端据以渲染身份行；filter 已验会话）。 */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(HttpServletRequest request) {
        Object role = request.getAttribute(AdminSessionFilter.ROLE_ATTRIBUTE);
        return ResponseEntity.ok(new MeResponse(
                String.valueOf(request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE)),
                (Long) request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE),
                role == null ? null : role.toString(),
                permissionsOfRequest(request)));
    }

    /** 登录请求体（{@code totpCode} 仅当账号启用 TOTP 时必填）。 */
    public record LoginRequest(String username, String password, String totpCode) {
    }

    /** 取来源 IP（优先反代传递的 X-Forwarded-For 首段）。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 登录成功响应：明文令牌（仅此一次）+ 主体 + 平台能力名（前端显示分区用）。 */
    public record LoginResponse(String token, String subjectType, Long subjectId, String role,
                                List<String> permissions) {
    }

    /** 当前主体（含平台能力名）。 */
    public record MeResponse(String subjectType, Long subjectId, String role,
                             List<String> permissions) {
    }

    /** 主体的平台能力名：超管天然全权（全枚举）；其余走账本。供前端按权限做显示分区。 */
    private List<String> permissionsOfSubject(AdminAuthService.Authenticated subject) {
        if (subject.role() == AdminRole.SUPER_ADMIN) {
            return allPermissionNames();
        }
        return grantedNames(subject.subjectType(), subject.subjectId());
    }

    /** 会话主体的能力名（属性由 {@code AdminSessionFilter} 写入 request）。 */
    private List<String> permissionsOfRequest(HttpServletRequest request) {
        if (request.getAttribute(AdminSessionFilter.ROLE_ATTRIBUTE) == AdminRole.SUPER_ADMIN) {
            return allPermissionNames();
        }
        return grantedNames(
                (ActorType) request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE),
                (Long) request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE));
    }

    private static List<String> allPermissionNames() {
        return Arrays.stream(PlatformPermission.values()).map(Enum::name).sorted().toList();
    }

    private List<String> grantedNames(ActorType subjectType, Long subjectId) {
        return grants.permissionsOf(subjectType, subjectId).stream().map(Enum::name).sorted().toList();
    }
}
