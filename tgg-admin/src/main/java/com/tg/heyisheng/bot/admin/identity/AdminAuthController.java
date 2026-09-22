package com.tg.heyisheng.bot.admin.identity;

import com.tg.heyisheng.bot.admin.AdminApiTokenCondition;
import com.tg.heyisheng.bot.admin.AdminProperties;
import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.platform.PlatformGrantSource;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);

    private final AdminAuthService auth;
    private final AdminProperties properties;
    private final TelegramLoginVerifier telegramVerifier;
    private final AdminLoginRateLimiter rateLimiter;
    private final PlatformGrantSource grants;
    private final TgLoginAllowlist tgLoginAllowlist;
    private final IdHasher idHasher;
    /** 两步验证的**自助**绑定/解绑走这里（密钥只回给本人，见下游 javadoc）。 */
    private final AccountAdminService accountAdmin;

    public AdminAuthController(AdminAuthService auth, AdminProperties properties,
                               org.springframework.beans.factory.ObjectProvider<TelegramLoginVerifier> verifier,
                               org.springframework.beans.factory.ObjectProvider<AdminLoginRateLimiter> rateLimiter,
                               PlatformGrantSource grants,
                               org.springframework.beans.factory.ObjectProvider<TgLoginAllowlist> tgLoginAllowlist,
                               IdHasher idHasher,
                               AccountAdminService accountAdmin) {
        this.auth = auth;
        this.properties = properties;
        this.telegramVerifier = verifier.getIfAvailable();
        this.rateLimiter = rateLimiter.getIfAvailable();
        this.grants = grants;
        this.tgLoginAllowlist = tgLoginAllowlist.getIfAvailable();
        this.idHasher = idHasher;
        this.accountAdmin = accountAdmin;
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
        // 验签只证明「数据来自 Telegram」，不证明「此人被授权进后台」——任何 TG 用户都能对该 bot
        // 完成 Login Widget，故必须再过白名单闸门（fail-closed：白名单为空即无人可登）。
        if (tgLoginAllowlist == null || !tgLoginAllowlist.allows(user.get().userId())) {
            log.warn("TG 登录被拒：该 Telegram 用户不在白名单内（userIdHash={}）",
                    idHasher.hash(user.get().userId()));
            return ResponseEntity.status(403).body(Map.of("error", "该 Telegram 账号未被授权访问后台"));
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

    // ───────────────────── 两步验证（TOTP）· 自助绑定 ─────────────────────

    /**
     * `POST /admin/auth/totp` —— **本人**启用两步验证，返回 {@code otpauth://} URL（含密钥，仅此一次）。
     *
     * <p>密钥只回给发起者<b>本人</b>——这正是「自助绑定」的全部意义。原先超管那条
     * 「替他人启用并取回密钥」的端点已移除：密钥被第三方看到，第二因子对那个第三方即失效。
     */
    @PostMapping("/totp")
    public ResponseEntity<?> enableTotp(HttpServletRequest request) {
        Long accountId = currentAccountId(request);
        if (accountId == null) {
            return ResponseEntity.status(400)
                    .body(Map.of("error", "当前主体不是后台账号（Telegram 登录无本地账号），无法启用两步验证"));
        }
        try {
            return ResponseEntity.ok(Map.of("otpauthUrl", accountAdmin.enableTotp(accountId)));
        } catch (IllegalArgumentException ex) {
            // 已启用：409（与「参数错」的 400 区分开，便于前端提示不同文案）
            return ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * `DELETE /admin/auth/totp?code=123456` —— **本人**解绑，必须带当前有效验证码。
     *
     * <p>要验证码是为了「拿到一个活动会话也关不掉第二因子」——否则第二因子白设。
     */
    @DeleteMapping("/totp")
    public ResponseEntity<?> disableTotp(
            @RequestParam(name = "code", required = false) String code, HttpServletRequest request) {
        Long accountId = currentAccountId(request);
        if (accountId == null) {
            return ResponseEntity.status(400)
                    .body(Map.of("error", "当前主体不是后台账号（Telegram 登录无本地账号），无法解绑两步验证"));
        }
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "解绑两步验证需要当前验证码（?code=123456）"));
        }
        try {
            accountAdmin.disableTotpWithCode(accountId, code.trim());
            return ResponseEntity.ok(Map.of("result", "OK"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** 当前会话对应的**后台账号** id；主体不是后台账号（如 Telegram 用户）时返回 null。 */
    private static Long currentAccountId(HttpServletRequest request) {
        Object type = request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE);
        Object id = request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE);
        return type == ActorType.ADMIN_ACCOUNT && id instanceof Long value ? value : null;
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

    /**
     * 取来源 IP，用作登录限流的键。
     *
     * <p><b>为什么不默认采信 {@code X-Forwarded-For}</b>：其首段完全由客户端控制——轮换该头即可
     * 绕过「按来源 IP 的登录限流」，使防跨账号爆破<b>静默失效</b>。默认取 {@code getRemoteAddr()}
     * （TCP 对端地址，不可伪造）。仅当部署保证「应用只接受可信反向代理的连接」（直连不可达）时，
     * 才置 {@code tgg.admin.trust-forwarded-for=true}；此时取 XFF 的<b>最后一跳</b>——
     * 那是直连的反代追加的，客户端无法移除或覆盖。
     */
    String clientIp(HttpServletRequest request) {
        if (properties.isTrustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                String lastHop = hops[hops.length - 1].trim();
                if (!lastHop.isEmpty()) {
                    return lastHop;
                }
            }
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
