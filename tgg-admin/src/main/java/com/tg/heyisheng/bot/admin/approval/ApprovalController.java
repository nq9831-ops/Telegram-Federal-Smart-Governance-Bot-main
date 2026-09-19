package com.tg.heyisheng.bot.admin.approval;

import com.tg.heyisheng.bot.admin.AdminApiTokenCondition;
import com.tg.heyisheng.bot.admin.AdminAuthFilter;
import com.tg.heyisheng.bot.common.exception.TggException;
import com.tg.heyisheng.bot.core.moderation.ReviewStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 审批中心 REST 端点（模块十一 §12.1）。
 *
 * <pre>
 * GET  /admin/approvals?status=PENDING&amp;page=0&amp;size=20   待办列表（按优先级排序）
 * GET  /admin/approvals/stats                             统计
 * GET  /admin/approvals/{id}                              详情（不存在 → 404）
 * POST /admin/approvals/{id}/decide                       裁决（body: decision + reason）
 * </pre>
 *
 * <p><b>鉴权不在本类</b>：{@link AdminAuthFilter} 已在进门前验明 token 与 operator 白名单，
 * 并把 operator 放进请求属性。本类只取用，不重复判定——门禁只有一处实现，才不会出现
 * 「某个端点漏判」这种最典型的越权。
 *
 * <p><b>状态码显式设置（重要）</b>：本项目有一个全局 {@code @RestControllerAdvice}
 * 把**业务异常**统一吞成 200（为 Telegram 避免重试风暴的既有设计）。若这里抛异常来表达 404/403，
 * 调用方会收到 200 —— 故本类<b>一律用 {@code ResponseEntity} 返回状态码，不靠异常</b>，
 * 并把它写进集成测试。（唯一的例外是**未知路径**：{@code NoResourceFoundException} 已由该处理器
 * 放行为 404，见 {@code UnknownPathIT}——但那是「路径不存在」，不是本类要表达的「资源不存在」。）
 */
@RestController
@Conditional(AdminApiTokenCondition.class)
@RequestMapping(path = "/admin/approvals", produces = MediaType.APPLICATION_JSON_VALUE)
public class ApprovalController {

    private final ApprovalQueryService queries;
    private final ApprovalCommandService commands;

    public ApprovalController(ApprovalQueryService queries, ApprovalCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    /** 待办列表；{@code PENDING} 按「硬红线 &gt; 等级 &gt; 先入先审」排序。 */
    @GetMapping
    public ApprovalQueryService.Page list(
            @RequestParam(defaultValue = "PENDING") ReviewStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.list(status, page, size);
    }

    /**
     * 统计。
     *
     * <p>路径刻意声明在 {@code /{id}} <b>之前</b>——Spring MVC 虽按「更具体者优先」匹配，
     * 但把字面量路径写在前面能让后来者一眼看出它不会被 {@code /{id}} 吃掉。
     */
    @GetMapping("/stats")
    public ApprovalQueryService.Stats stats() {
        return queries.stats();
    }

    /** 单条详情；不存在返回 404（而非被异常处理器吞成 200）。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApprovalQueryService.Item> detail(@PathVariable long id) {
        return queries.find(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 裁决一条待办。 */
    @PostMapping(path = "/{id}/decide", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> decide(@PathVariable long id,
                                                      @RequestBody DecideRequest body,
                                                      HttpServletRequest request) {
        ReviewStatus decision = parseDecision(body.decision());
        if (decision == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "decision 只能是 APPROVED 或 REJECTED"));
        }
        Long operator = (Long) request.getAttribute(AdminAuthFilter.OPERATOR_ATTRIBUTE);

        try {
            ApprovalCommandService.Outcome outcome = commands.decide(id, decision, operator, body.reason());
            return switch (outcome.result()) {
                case NOT_FOUND -> ResponseEntity.notFound().build();
                case SELF_DECISION_FORBIDDEN -> ResponseEntity.status(403)
                        .body(Map.of("error", "审批人不可审批自己的案件"));
                case DECIDED, ALREADY_DECIDED -> ResponseEntity.ok(Map.of(
                        "result", outcome.result().name(),
                        "status", String.valueOf(outcome.status())));
            };
        } catch (TggException ex) {
            // 备注超长等输入问题：如实回显原因（core 的裁决服务已把语义校验做完）
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** 只接受维持/推翻两种结论——与 core 裁决服务的约束保持一致。 */
    private static ReviewStatus parseDecision(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        ReviewStatus parsed;
        try {
            parsed = ReviewStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return (parsed == ReviewStatus.APPROVED || parsed == ReviewStatus.REJECTED) ? parsed : null;
    }

    /** 裁决请求体。 */
    public record DecideRequest(String decision, String reason) {
    }
}
