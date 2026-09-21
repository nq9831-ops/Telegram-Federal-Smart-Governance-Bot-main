package com.tg.heyisheng.bot.admin.system;

import com.tg.heyisheng.bot.admin.AdminApiTokenCondition;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.AdminSessionFilter;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.audit.AuditEntry;
import com.tg.heyisheng.bot.core.audit.AuditService;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统操作端点（模块十一 扩展）。
 *
 * <pre>
 * POST /admin/system/restart   触发一次优雅重启（需配置写权限 + tgg.admin.restart-enabled=true）
 * </pre>
 *
 * <p><b>⚠️ 本端点只负责「优雅退出」，不负责「再起来」</b>——由外部监管进程拉起：
 * Docker {@code restart: unless-stopped} / systemd {@code Restart=always} / k8s Deployment（容器退出即重建）。
 * <b>裸 {@code java -jar} 下点了按钮＝停服不起</b>，故默认关闭（{@code tgg.admin.restart-enabled=false}）。
 *
 * <p>权限：<b>超管天然全权</b>，其余走 {@link ConfigAdminGuard}。状态码：无权限 → 403；未启用 → 409；
 * 放行 → **202 Accepted**。
 */
@RestController
@Conditional(AdminApiTokenCondition.class)
@RequestMapping(path = "/admin/system", produces = MediaType.APPLICATION_JSON_VALUE)
public class SystemController {

    static final String AUDIT_ACTION = "admin.system.restart";

    /** 是否允许经 Web 重启的门控键。 */
    static final String RESTART_ENABLED_KEY = "tgg.admin.restart-enabled";

    private final RuntimeConfigService config;
    private final AuditService audit;
    private final RestartAction restartAction;

    public SystemController(RuntimeConfigService config, AuditService audit, RestartAction restartAction) {
        this.config = config;
        this.audit = audit;
        this.restartAction = restartAction;
    }

    /** 触发优雅重启。 */
    @PostMapping("/restart")
    public ResponseEntity<Map<String, String>> restart(HttpServletRequest request) {
        boolean superAdmin = request.getAttribute(AdminSessionFilter.ROLE_ATTRIBUTE) == AdminRole.SUPER_ADMIN;
        ActorType actorType = (ActorType) request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE);
        Long actorId = (Long) request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE);
        if (!superAdmin) {
            // 重启是本系统唯一的不可逆对外动作：非超管不得直接执行，须走双人复核
            // （POST /admin/dangerous-actions 发起 → 超管批准）。超管不受此限（单超管下否则会死锁）。
            return ResponseEntity.status(403).body(Map.of("error",
                    "重启需双人复核：非超管请先经 POST /admin/dangerous-actions 发起，由超管批准"));
        }
        if (!config.getBoolean(RESTART_ENABLED_KEY, false)) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "重启未启用：请先设置 " + RESTART_ENABLED_KEY
                            + "=true（无外部监管进程的部署请勿开启——退出后不会被拉起）"));
        }

        audit.record(actorType, actorId, AUDIT_ACTION, null, AuditEntry.Outcome.SUCCESS, "restart requested");
        restartAction.restart();
        return ResponseEntity.accepted().body(Map.of("result", "RESTARTING"));
    }
}
