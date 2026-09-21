package com.tg.heyisheng.bot.admin.config;

import com.tg.heyisheng.bot.admin.AdminApiTokenCondition;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.AdminSessionFilter;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.audit.AuditEntry;
import com.tg.heyisheng.bot.core.audit.AuditService;
import com.tg.heyisheng.bot.core.config.dynamic.ConfigAdminGuard;
import com.tg.heyisheng.bot.core.config.dynamic.ConfigCatalog;
import com.tg.heyisheng.bot.core.config.dynamic.ConfigKey;
import com.tg.heyisheng.bot.core.config.dynamic.ConfigWriteException;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 配置中心端点（模块十一 扩展）。
 *
 * <pre>
 * GET    /admin/config        全量只读总览（任一已鉴权主体）
 * PUT    /admin/config/{key}  写一条覆盖（需配置写权限）
 * DELETE /admin/config/{key}  清除覆盖，回落到环境/default（需配置写权限）
 * </pre>
 *
 * <p><b>鉴权分两层</b>：粗门禁由 {@link AdminSessionFilter} 在 {@code /admin/*} 前置做（会话有效性）；
 * <b>写</b>另加一层：<b>超管天然全权</b>，其余主体走 {@link ConfigAdminGuard} 白名单。
 * 读对任一已鉴权者开放，写才更严——因为读只是看，写能开关模块、改阈值、触发重启。
 *
 * <p><b>状态码用 {@code ResponseEntity} 显式返回</b>（不靠抛异常）：本项目有全局异常处理器把业务异常
 * 吞成 200，抛异常表达 404/409 会得到 200。分类映射：键不存在 → 404；键不可写 → 409；值非法 → 400。
 *
 * <p><b>审计</b>：每次写/清都记 {@code admin.config.update}，明细**只记键不放值**（密钥类虽已被
 * 分类拦在写路径外，仍不把值写进审计，少一条泄漏面）；主体带类型（{@link ActorType}）。
 */
@RestController
@Conditional(AdminApiTokenCondition.class)
@RequestMapping(path = "/admin/config", produces = MediaType.APPLICATION_JSON_VALUE)
public class ConfigController {

    /** 审计动作标识。 */
    static final String AUDIT_ACTION = "admin.config.update";

    private final RuntimeConfigService config;
    private final ConfigAdminGuard guard;
    private final AuditService audit;

    public ConfigController(RuntimeConfigService config, ConfigAdminGuard guard, AuditService audit) {
        this.config = config;
        this.guard = guard;
        this.audit = audit;
    }

    /** 全量配置总览（只读）。 */
    @GetMapping
    public List<RuntimeConfigService.Resolved> list() {
        return config.snapshot();
    }

    /** 写权限名单的来源（只读信息）。 */
    @GetMapping("/permissions")
    public WritePermission permissions() {
        return new WritePermission(guard.source(), guard.configAdmins().size());
    }

    /** 写权限名单来源。 */
    public record WritePermission(String source, int count) {
    }

    /** 写一条配置覆盖。 */
    @PutMapping(path = "/{key}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> update(@PathVariable String key,
                                                      @RequestBody UpdateRequest body,
                                                      HttpServletRequest request) {
        if (!mayWrite(request)) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "无配置写权限：当前主体不是超管，且不在配置管理员白名单内"));
        }
        ActorType actorType = actorType(request);
        Long actorId = actorId(request);
        try {
            String before = config.resolve(key).orElse(null);
            String value = config.set(key, body.value(), actorId);
            audit.record(actorType, actorId, AUDIT_ACTION, null, AuditEntry.Outcome.SUCCESS,
                    "set " + key + " old=" + masked(key, before) + " new=" + masked(key, value));
            return ResponseEntity.ok(Map.of("result", "UPDATED", "key", key, "effectiveValue", value));
        } catch (ConfigWriteException ex) {
            audit.record(actorType, actorId, AUDIT_ACTION, null, AuditEntry.Outcome.FAILURE,
                    "rejected " + key + " (" + ex.kind() + ")");
            return switch (ex.kind()) {
                case UNKNOWN_KEY -> ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
                case NOT_WRITABLE -> ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
                case INVALID_VALUE -> ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
            };
        }
    }

    /** 清除一条覆盖（回落到环境变量/默认）。 */
    @DeleteMapping(path = "/{key}")
    public ResponseEntity<Map<String, String>> clear(@PathVariable String key,
                                                     HttpServletRequest request) {
        if (!mayWrite(request)) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "无配置写权限：当前主体不是超管，且不在配置管理员白名单内"));
        }
        ActorType actorType = actorType(request);
        Long actorId = actorId(request);
        try {
            String before = config.resolve(key).orElse(null);
            config.clear(key, actorId);
            audit.record(actorType, actorId, AUDIT_ACTION, null, AuditEntry.Outcome.SUCCESS,
                    "clear " + key + " old=" + masked(key, before));
            return ResponseEntity.ok(Map.of("result", "CLEARED", "key", key));
        } catch (ConfigWriteException ex) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * 写权限判定：<b>超管天然全权</b>（用户要求「超管能做任何事情」），其余按配置写白名单。
     *
     * <p>Wave 2 引入细粒度能力授权后，白名单会被 {@code platform_grants} 取代；
     * 超管 bypass 保留（超管不受能力清单限制）。
     */
    private boolean mayWrite(HttpServletRequest request) {
        return isSuperAdmin(request) || guard.isConfigAdmin(actorType(request), actorId(request));
    }

    private static boolean isSuperAdmin(HttpServletRequest request) {
        return request.getAttribute(AdminSessionFilter.ROLE_ATTRIBUTE) == AdminRole.SUPER_ADMIN;
    }

    private static ActorType actorType(HttpServletRequest request) {
        return (ActorType) request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE);
    }

    private static Long actorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE);
    }

    /** 审计明细用的值展示：密钥类键打码，未设置为占位符。 */
    private static String masked(String key, String value) {
        if (value == null) {
            return "<未设置>";
        }
        boolean secret = ConfigCatalog.find(key).map(ConfigKey::secret).orElse(false);
        return secret ? "***" : value;
    }

    /** 写入请求体：只带一个值（键在路径里）。 */
    public record UpdateRequest(String value) {
    }
}
