package com.tg.heyisheng.bot.admin.audit;

import com.tg.heyisheng.bot.admin.AdminApiTokenCondition;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.AdminSessionFilter;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.audit.AuditEntry;
import com.tg.heyisheng.bot.core.audit.AuditLogRepository;
import com.tg.heyisheng.bot.core.platform.PlatformGrantSource;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 审计视图（模块十一 · 护栏）——<b>只读</b>。
 *
 * <pre>
 * GET /admin/audit/recent?limit=100   最近若干条审计（倒序）
 * </pre>
 *
 * <p><b>只读且只追加</b>：本端点不提供任何删除/修改入口（审计仓库本身也刻意不暴露删除，
 * 见 {@code AuditLogRepository}）。
 *
 * <p><b>权限</b>：超管天然全权；其余主体需持有 {@link PlatformPermission#AUDIT_READ}
 * （由超管在账号管理页授予）。
 */
@RestController
@Conditional(AdminApiTokenCondition.class)
@RequestMapping(path = "/admin/audit", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuditViewController {

    /** 单次返回上限（防止一次拉爆）。 */
    static final int MAX_LIMIT = 200;

    private final AuditLogRepository audit;
    private final PlatformGrantSource grants;

    public AuditViewController(AuditLogRepository audit, PlatformGrantSource grants) {
        this.audit = audit;
        this.grants = grants;
    }

    /** 最近若干条审计（倒序）。 */
    @GetMapping("/recent")
    public ResponseEntity<?> recent(@RequestParam(defaultValue = "100") int limit,
                                    HttpServletRequest request) {
        if (!mayRead(request)) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "无审计查看权限：当前主体不是超管，且未持有 AUDIT_READ"));
        }
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Map<String, Object>> rows = audit.findByOrderByIdDesc(PageRequest.of(0, bounded)).stream()
                .map(AuditViewController::toView)
                .toList();
        return ResponseEntity.ok(rows);
    }

    /** 读权限：超管天然全权；其余走账本的 AUDIT_READ（配置键无对应项，故不回落）。 */
    private boolean mayRead(HttpServletRequest request) {
        if (request.getAttribute(AdminSessionFilter.ROLE_ATTRIBUTE) == AdminRole.SUPER_ADMIN) {
            return true;
        }
        ActorType subjectType = (ActorType) request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE);
        Long subjectId = (Long) request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE);
        return grants.hasPermission(subjectType, subjectId, PlatformPermission.AUDIT_READ);
    }

    private static Map<String, Object> toView(AuditEntry e) {
        // 用 Map 而非记录：允许 null 值（记录 + Map.of 不允许 null）
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", e.getId());
        view.put("actorType", e.getActorType() == null ? null : e.getActorType().name());
        view.put("actorId", e.getActorId());
        view.put("action", e.getAction());
        view.put("target", e.getTarget());
        view.put("caseId", e.getCaseId());
        view.put("outcome", e.getOutcome() == null ? null : e.getOutcome().name());
        view.put("detail", e.getDetail());
        view.put("occurredAt", String.valueOf(e.getOccurredAt()));
        return view;
    }
}
