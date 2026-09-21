package com.tg.heyisheng.bot.admin.identity;

import com.tg.heyisheng.bot.admin.AdminApiTokenCondition;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.audit.AuditEntry;
import com.tg.heyisheng.bot.core.audit.AuditService;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * 账号管理端点（模块十一 · 权限模型）——<b>仅超级管理员</b>。
 *
 * <pre>
 * GET  /admin/accounts                        列出全部账号与其能力
 * POST /admin/accounts                        建操作员（role 恒为 OPERATOR）
 * PUT  /admin/accounts/{id}/status            停用 / 启用
 * PUT  /admin/accounts/{id}/password          重置密码（改后强制下线）
 * PUT  /admin/accounts/{id}/permissions       设置能力清单
 * POST /admin/accounts/{id}/revoke-sessions   强制下线
 * </pre>
 *
 * <p><b>为什么「仅超管」是核心防线</b>：控制台是本模块唯一的权限写入口。把它的准入收成「仅超管」，
 * 就从结构上排除了「操作员给自己或他人加权限」这条提权路——无需再逐处判「是否自授权」。
 *
 * <p><b>状态码用 {@code ResponseEntity} 显式返回</b>（本项目全局异常处理器会把业务异常吞成 200）。
 * 审计主体为 ADMIN_ACCOUNT（操作者账号），动作形如 {@code admin.account.*}。
 */
@RestController
@Conditional(AdminApiTokenCondition.class)
@RequestMapping(path = "/admin/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
public class AccountAdminController {

    static final String AUDIT_CREATE = "admin.account.create";
    static final String AUDIT_STATUS = "admin.account.status";
    static final String AUDIT_PASSWORD = "admin.account.password";
    static final String AUDIT_PERMISSIONS = "admin.account.permissions";
    static final String AUDIT_REVOKE = "admin.account.revoke-sessions";
    static final String AUDIT_TOTP = "admin.account.totp";

    private final AccountAdminService service;
    private final AuditService audit;

    public AccountAdminController(AccountAdminService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        if (!isSuperAdmin(request)) {
            return forbidden();
        }
        return ResponseEntity.ok(service.list());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody CreateRequest body, HttpServletRequest request) {
        if (!isSuperAdmin(request)) {
            return forbidden();
        }
        try {
            AdminAccount created = service.createOperator(body.username(), body.password());
            auditRequest(request, AUDIT_CREATE, "create " + created.getUsername());
            return ResponseEntity.ok(Map.of("id", created.getId(), "username", created.getUsername()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping(path = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> status(@PathVariable Long id, @RequestBody StatusRequest body,
                                    HttpServletRequest request) {
        if (!isSuperAdmin(request)) {
            return forbidden();
        }
        try {
            AdminStatus status = AdminStatus.valueOf(String.valueOf(body.status()).trim().toUpperCase());
            service.setStatus(id, status);
            auditRequest(request, AUDIT_STATUS, "account " + id + " -> " + status);
            return ResponseEntity.ok(Map.of("result", "OK"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping(path = "/{id}/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> password(@PathVariable Long id, @RequestBody PasswordRequest body,
                                      HttpServletRequest request) {
        if (!isSuperAdmin(request)) {
            return forbidden();
        }
        try {
            service.resetPassword(id, body.password());
            auditRequest(request, AUDIT_PASSWORD, "account " + id + " password reset");
            return ResponseEntity.ok(Map.of("result", "OK"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping(path = "/{id}/permissions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> permissions(@PathVariable Long id, @RequestBody PermissionsRequest body,
                                         HttpServletRequest request) {
        if (!isSuperAdmin(request)) {
            return forbidden();
        }
        try {
            Long by = (Long) request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE);
            service.setPermissions(id, body.permissions(), by);
            auditRequest(request, AUDIT_PERMISSIONS, "account " + id + " -> " + body.permissions());
            return ResponseEntity.ok(Map.of("result", "OK"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/revoke-sessions")
    public ResponseEntity<?> revokeSessions(@PathVariable Long id, HttpServletRequest request) {
        if (!isSuperAdmin(request)) {
            return forbidden();
        }
        try {
            int revoked = service.revokeSessions(id);
            auditRequest(request, AUDIT_REVOKE, "account " + id + " sessions revoked=" + revoked);
            return ResponseEntity.ok(Map.of("revoked", revoked));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** 启用 TOTP：生成本账号的密钥并返回 otpauth URL（供 authenticator app 扫码）。 */
    @PostMapping("/{id}/totp")
    public ResponseEntity<?> enableTotp(@PathVariable Long id, HttpServletRequest request) {
        if (!isSuperAdmin(request)) {
            return forbidden();
        }
        try {
            String url = service.enableTotp(id);
            auditRequest(request, AUDIT_TOTP, "account " + id + " totp enabled");
            return ResponseEntity.ok(Map.of("otpauthUrl", url));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** 关闭 TOTP 第二因子。 */
    @DeleteMapping("/{id}/totp")
    public ResponseEntity<?> disableTotp(@PathVariable Long id, HttpServletRequest request) {
        if (!isSuperAdmin(request)) {
            return forbidden();
        }
        try {
            service.disableTotp(id);
            auditRequest(request, AUDIT_TOTP, "account " + id + " totp disabled");
            return ResponseEntity.ok(Map.of("result", "OK"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    private static boolean isSuperAdmin(HttpServletRequest request) {        return request.getAttribute(AdminSessionFilter.ROLE_ATTRIBUTE) == AdminRole.SUPER_ADMIN;
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "仅超级管理员可管理账号"));
    }

    /** 记一条后台操作审计（主体＝当前超管账号）。 */
    private void auditRequest(HttpServletRequest request, String action, String detail) {
        ActorType subjectType = (ActorType) request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE);
        Long subjectId = (Long) request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE);
        audit.record(subjectType, subjectId, action, null, AuditEntry.Outcome.SUCCESS, detail);
    }

    public record CreateRequest(String username, String password) {
    }

    public record StatusRequest(String status) {
    }

    public record PasswordRequest(String password) {
    }

    public record PermissionsRequest(Set<PlatformPermission> permissions) {
    }
}
