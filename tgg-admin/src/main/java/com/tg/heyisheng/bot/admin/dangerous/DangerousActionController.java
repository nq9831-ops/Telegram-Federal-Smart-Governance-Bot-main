package com.tg.heyisheng.bot.admin.dangerous;

import com.tg.heyisheng.bot.admin.AdminApiTokenCondition;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.AdminSessionFilter;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.platform.PlatformGrantSource;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 危险动作双人复核端点（模块十一 · 护栏）。
 *
 * <pre>
 * POST /admin/dangerous-actions              发起（需 SYSTEM_RESTART）
 * GET  /admin/dangerous-actions              待批准队列（仅超管）
 * POST /admin/dangerous-actions/{id}/approve 批准并执行（仅超管；发起人不得自批）
 * POST /admin/dangerous-actions/{id}/reject  拒绝（仅超管）
 * </pre>
 *
 * <p><b>为什么发起与批准分属不同权限</b>：发起只需 {@link PlatformPermission#SYSTEM_RESTART}
 * （操作员可请求），而<b>批准必须是超管</b>——这正是"双人"的权限落点。
 *
 * <p>状态码用 {@code ResponseEntity} 显式返回（本项目全局异常处理器会把业务异常吞成 200）。
 */
@RestController
@Conditional(AdminApiTokenCondition.class)
@RequestMapping(path = "/admin/dangerous-actions", produces = MediaType.APPLICATION_JSON_VALUE)
public class DangerousActionController {

    private final DangerousActionService service;
    private final PlatformGrantSource grants;

    public DangerousActionController(DangerousActionService service, PlatformGrantSource grants) {
        this.service = service;
        this.grants = grants;
    }

    /** 发起一条待批准的重启请求。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> request(@RequestBody InitiateRequest body, HttpServletRequest request) {
        ActorType subjectType = subjectType(request);
        Long subjectId = subjectId(request);
        boolean superAdmin = isSuperAdmin(request);
        if (!superAdmin && !grants.hasPermission(subjectType, subjectId, PlatformPermission.SYSTEM_RESTART)) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "无发起权限：当前主体不是超管，且未持有 SYSTEM_RESTART"));
        }
        DangerousActionRequest.ActionType type = parseType(body == null ? null : body.actionType());
        if (type == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "actionType 只能是 RESTART"));
        }
        DangerousActionService.Outcome outcome = service.request(type, subjectType, subjectId);
        return ResponseEntity.accepted()
                .body(Map.of("requestId", outcome.requestId(), "status", "PENDING",
                        "note", "已提交待批准：需另一主体（超管）批准后才会执行"));
    }

    /** 待批准队列（仅超管）。 */
    @GetMapping
    public ResponseEntity<?> pending(HttpServletRequest request) {
        if (!isSuperAdmin(request)) {
            return ResponseEntity.status(403).body(Map.of("error", "仅超级管理员可查看/批准待办"));
        }
        List<Map<String, Object>> rows = service.pending().stream()
                .map(DangerousActionController::toView)
                .toList();
        return ResponseEntity.ok(rows);
    }

    /** 批准并执行（仅超管；发起人不得自批）。 */
    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id, HttpServletRequest request) {
        if (!isSuperAdmin(request)) {
            return ResponseEntity.status(403).body(Map.of("error", "仅超级管理员可批准危险动作"));
        }
        DangerousActionService.Outcome outcome = service.approve(id, subjectId(request));
        return switch (outcome.result()) {
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case ALREADY_DECIDED -> ResponseEntity.status(409)
                    .body(Map.of("error", "该请求已裁决（" + outcome.status() + "）"));
            case SAME_SUBJECT_FORBIDDEN -> ResponseEntity.status(403)
                    .body(Map.of("error", "发起人不得自行批准——该动作需另一主体复核"));
            case APPROVED -> ResponseEntity.ok(Map.of("result", "APPROVED"));
            default -> ResponseEntity.badRequest().body(Map.of("error", outcome.result().name()));
        };
    }

    /** 拒绝（仅超管，不执行）。 */
    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable Long id, HttpServletRequest request) {
        if (!isSuperAdmin(request)) {
            return ResponseEntity.status(403).body(Map.of("error", "仅超级管理员可拒绝危险动作"));
        }
        DangerousActionService.Outcome outcome = service.reject(id, subjectId(request), null);
        return switch (outcome.result()) {
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case ALREADY_DECIDED -> ResponseEntity.status(409)
                    .body(Map.of("error", "该请求已裁决（" + outcome.status() + "）"));
            case REJECTED -> ResponseEntity.ok(Map.of("result", "REJECTED"));
            default -> ResponseEntity.badRequest().body(Map.of("error", outcome.result().name()));
        };
    }

    private static boolean isSuperAdmin(HttpServletRequest request) {
        return request.getAttribute(AdminSessionFilter.ROLE_ATTRIBUTE) == AdminRole.SUPER_ADMIN;
    }

    private static ActorType subjectType(HttpServletRequest request) {
        return (ActorType) request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE);
    }

    private static Long subjectId(HttpServletRequest request) {
        return (Long) request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE);
    }

    private static DangerousActionRequest.ActionType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DangerousActionRequest.ActionType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Map<String, Object> toView(DangerousActionRequest r) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", r.getId());
        view.put("actionType", r.getActionType().name());
        view.put("requestedById", r.getRequestedById());
        view.put("status", r.getStatus().name());
        view.put("createdAt", String.valueOf(r.getCreatedAt()));
        return view;
    }

    /** 发起请求体。 */
    public record InitiateRequest(String actionType) {
    }
}
