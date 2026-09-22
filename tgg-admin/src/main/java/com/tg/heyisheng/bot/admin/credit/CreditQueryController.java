package com.tg.heyisheng.bot.admin.credit;

import com.tg.heyisheng.bot.admin.AdminApiTokenCondition;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.AdminSessionFilter;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import com.tg.heyisheng.bot.core.platform.PlatformGrantSource;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;
import com.tg.heyisheng.bot.credit.CreditEventRecord;
import com.tg.heyisheng.bot.credit.CreditEventRecordRepository;
import com.tg.heyisheng.bot.credit.CreditScore;
import com.tg.heyisheng.bot.credit.CreditScoreRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 信用可见面（模块十一 · 护栏）——<b>只读</b>。
 *
 * <pre>
 * GET /admin/credit/events?subjectType=&amp;subjectId=&amp;page=0&amp;size=50   信用流水（新在前）
 * GET /admin/credit/scores?subjectType=&amp;subjectId=&amp;page=0&amp;size=50   信用账本（新在前）
 * </pre>
 *
 * <p><b>缺陷背景</b>：在此之前 {@code credit_events} / {@code credit_scores} 对<b>所有</b>角色
 * 零可见面——运营者能扣分却看不到扣了什么、账本现在多少，审计无从对账。本类补上这唯一入口。
 *
 * <p><b>只读</b>：不提供任何写/删入口。流水本身只追加（见 {@code CreditEventRecord}），
 * 账本由 {@code CreditService} 在业务链里改；后台只投影、绝不改。
 *
 * <p><b>权限</b>：超管天然全权；其余主体需持 {@link PlatformPermission#CREDIT_READ}
 * （由超管在账号管理页授予）。判据与 {@code AuditViewController} 同源——一条 <b>fail-closed</b>
 * 门禁：无会话由 {@link AdminSessionFilter} 挡在 401，有会话但无读权限在这里挡成 403。
 *
 * <p><b>数据范围过滤进查询条件</b>（与「我的收录」同一纪律）：主体过滤下沉到仓库方法，
 * 绝不「查全部再在控制器里过滤」——后者一旦漏过滤就把别的行发出去。
 *
 * <p><b>主体 id 与类型成对</b>：{@code subjectId} 的数值空间在三种主体类型间重叠
 * （个人 userId / 群 chatId / 商户 id），故按 id 过滤时<b>必须</b>同时给出 {@code subjectType}，
 * 否则 400——单看 id 会串号（本项目审计页已踩过同一坑）。
 */
@RestController
@Conditional(AdminApiTokenCondition.class)
@RequestMapping(path = "/admin/credit", produces = MediaType.APPLICATION_JSON_VALUE)
public class CreditQueryController {

    /** 单页上限（防止一次拉爆）。 */
    static final int MAX_PAGE_SIZE = 200;
    /** 默认单页条数。 */
    static final int DEFAULT_PAGE_SIZE = 50;

    private final CreditEventRecordRepository events;
    private final CreditScoreRepository scores;
    private final PlatformGrantSource grants;

    public CreditQueryController(CreditEventRecordRepository events, CreditScoreRepository scores,
                                 PlatformGrantSource grants) {
        this.events = events;
        this.scores = scores;
        this.grants = grants;
    }

    /** 信用流水（倒序）。 */
    @GetMapping("/events")
    public ResponseEntity<?> events(@RequestParam(required = false) String subjectType,
                                    @RequestParam(required = false) Long subjectId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size,
                                    HttpServletRequest request) {
        if (!mayRead(request)) {
            return forbidden();
        }
        CreditSubjectType type;
        try {
            type = parseSubjectType(subjectType);
            requireTypeWithId(type, subjectId);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        Pageable pageable = pageable(page, size);
        Page<CreditEventRecord> result = queryEvents(type, subjectId, pageable);
        return ResponseEntity.ok(page(result.map(CreditQueryController::toEventView)));
    }

    /** 信用账本（倒序）。 */
    @GetMapping("/scores")
    public ResponseEntity<?> scores(@RequestParam(required = false) String subjectType,
                                    @RequestParam(required = false) Long subjectId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size,
                                    HttpServletRequest request) {
        if (!mayRead(request)) {
            return forbidden();
        }
        CreditSubjectType type;
        try {
            type = parseSubjectType(subjectType);
            requireTypeWithId(type, subjectId);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        Pageable pageable = pageable(page, size);
        Page<CreditScore> result = queryScores(type, subjectId, pageable);
        return ResponseEntity.ok(page(result.map(CreditQueryController::toScoreView)));
    }

    // ───────────────────────────── 权限 ─────────────────────────────

    /** 读权限：超管天然全权；其余走账本的 CREDIT_READ（配置键无对应项，故不回落）。 */
    private boolean mayRead(HttpServletRequest request) {
        if (request.getAttribute(AdminSessionFilter.ROLE_ATTRIBUTE) == AdminRole.SUPER_ADMIN) {
            return true;
        }
        ActorType subjectType = (ActorType) request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE);
        Long subjectId = (Long) request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE);
        return grants.hasPermission(subjectType, subjectId, PlatformPermission.CREDIT_READ);
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403)
                .body(Map.of("error", "无信用账本查看权限：当前主体不是超管，且未持有 CREDIT_READ"));
    }

    // ───────────────────────────── 查询 ─────────────────────────────

    private Page<CreditEventRecord> queryEvents(CreditSubjectType type, Long subjectId, Pageable pageable) {
        if (type == null) {
            return events.findAllByOrderByIdDesc(pageable);
        }
        if (subjectId == null) {
            return events.findBySubjectTypeOrderByIdDesc(type, pageable);
        }
        return events.findBySubjectTypeAndSubjectIdOrderByIdDesc(type, subjectId, pageable);
    }

    private Page<CreditScore> queryScores(CreditSubjectType type, Long subjectId, Pageable pageable) {
        if (type == null) {
            return scores.findAllByOrderByIdDesc(pageable);
        }
        if (subjectId == null) {
            return scores.findBySubjectTypeOrderByIdDesc(type, pageable);
        }
        return scores.findBySubjectTypeAndSubjectIdOrderByIdDesc(type, subjectId, pageable);
    }

    /** 分页入参：页码非负、页长落在 {@code [1, MAX_PAGE_SIZE]}。 */
    private static Pageable pageable(int page, int size) {
        int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        return PageRequest.of(Math.max(0, page), boundedSize);
    }

    /** 解析主体类型：缺省/空白 → null（不过滤）；非法值 → 400（不静默全量）。 */
    private static CreditSubjectType parseSubjectType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CreditSubjectType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知的 subjectType：" + raw
                    + "，可选值：" + Arrays.toString(CreditSubjectType.values()));
        }
    }

    /** 按 id 过滤必须同时给类型——id 空间在三种主体类型间重叠，单看 id 会串号。 */
    private static void requireTypeWithId(CreditSubjectType type, Long subjectId) {
        if (subjectId != null && type == null) {
            throw new IllegalArgumentException("按 subjectId 过滤时必须同时给出 subjectType（id 空间在类型间重叠）");
        }
    }

    // ───────────────────────────── 投影 ─────────────────────────────

    /** 用 Map 而非记录：允许 null 值（记录 + Map.of 不允许 null）。 */
    private static Map<String, Object> toEventView(CreditEventRecord e) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", e.getId());
        view.put("subjectType", e.getSubjectType() == null ? null : e.getSubjectType().name());
        view.put("subjectId", e.getSubjectId());
        view.put("eventType", e.getEventType() == null ? null : e.getEventType().name());
        view.put("severity", e.getSeverity() == null ? null : e.getSeverity().name());
        view.put("hardLine", e.isHardLine());
        view.put("scoreBefore", e.getScoreBefore());
        view.put("scoreDelta", e.getScoreDelta());
        view.put("scoreAfter", e.getScoreAfter());
        view.put("source", e.getSource());
        view.put("occurredAt", String.valueOf(e.getOccurredAt()));
        return view;
    }

    private static Map<String, Object> toScoreView(CreditScore s) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", s.getId());
        view.put("subjectType", s.getSubjectType() == null ? null : s.getSubjectType().name());
        view.put("subjectId", s.getSubjectId());
        view.put("score", s.getScore());
        view.put("updatedAt", String.valueOf(s.getUpdatedAt()));
        return view;
    }

    private static Map<String, Object> page(Page<Map<String, Object>> p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", p.getContent());
        body.put("total", p.getTotalElements());
        body.put("page", p.getNumber());
        body.put("size", p.getSize());
        return body;
    }
}
