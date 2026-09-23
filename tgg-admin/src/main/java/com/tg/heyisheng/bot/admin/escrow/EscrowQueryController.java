package com.tg.heyisheng.bot.admin.escrow;

import com.tg.heyisheng.bot.admin.AdminApiTokenCondition;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.AdminSessionFilter;
import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.platform.PlatformGrantSource;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;
import com.tg.heyisheng.bot.escrow.EscrowOrder;
import com.tg.heyisheng.bot.escrow.EscrowRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 担保交易订单可见面（模块十二 · 后台只读，闭合 gap-ESC-05）。
 *
 * <p><b>为什么必须有它</b>：{@code escrow_orders} 此前只能写——运营者与联邦裁决方
 * <b>没有任何界面能看到订单</b>，超时单与争议单会一直躺着。这正是 gap-07（处罚历史查询）同型缺口。
 *
 * <p><b>只读</b>：不提供任何写/删入口；状态推进只经 {@code EscrowService}（命令与裁决链）。
 *
 * <p><b>权限</b>：超管天然全权；其余主体需持 {@link PlatformPermission#FEDERATION_ADMIN}
 * ——担保交易的裁决方是联邦，语义贴切且复用既有权限点（不新增枚举）。fail-closed 口径与
 * {@code CreditQueryController} 同源：无会话由 {@link AdminSessionFilter} 挡成 401，
 * 有会话但无权限在这里挡成 403。
 *
 * <p><b>模块门控</b>：{@code tgg.escrow.enabled=false} 时本控制器不装配（端点不存在），
 * 与命令入口、状态机的门控一致。
 */
@RestController
@Conditional(AdminApiTokenCondition.class)
@ConditionalOnProperty(prefix = "tgg.escrow", name = "enabled", havingValue = "true")
@RequestMapping(path = "/admin/escrow", produces = MediaType.APPLICATION_JSON_VALUE)
public class EscrowQueryController {

    /** 单页上限（防止一次拉爆）。 */
    static final int MAX_PAGE_SIZE = 200;
    /** 默认单页条数。 */
    static final int DEFAULT_PAGE_SIZE = 50;

    private final EscrowRepository orders;
    private final PlatformGrantSource grants;

    public EscrowQueryController(EscrowRepository orders, PlatformGrantSource grants) {
        this.orders = orders;
        this.grants = grants;
    }

    /** 订单列表（新在前），可按状态筛选。 */
    @GetMapping("/orders")
    public ResponseEntity<?> orders(@RequestParam(required = false) String state,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size,
                                    HttpServletRequest request) {
        if (!mayRead(request)) {
            return forbidden();
        }
        String normalized = null;
        if (state != null && !state.isBlank()) {
            normalized = state.trim().toUpperCase(Locale.ROOT);
            if (!isKnownState(normalized)) {
                // 非法状态必须 400：静默忽略会让运营者以为"筛选生效了"却看到全量。
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "未知的订单状态：" + state));
            }
        }
        Pageable pageable = pageable(page, size);
        Page<EscrowOrder> result = normalized == null
                ? orders.findAllByOrderByIdDesc(pageable)
                : orders.findByStateOrderByIdDesc(normalized, pageable);
        return ResponseEntity.ok(page(result.map(EscrowQueryController::toView)));
    }

    /** 订单详情。 */
    @GetMapping("/orders/{id}")
    public ResponseEntity<?> order(@PathVariable long id, HttpServletRequest request) {
        if (!mayRead(request)) {
            return forbidden();
        }
        return orders.findById(id)
                .<ResponseEntity<?>>map(order -> ResponseEntity.ok(toView(order)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of("error", "订单不存在：#" + id)));
    }

    // ───────────────────────────── 权限 ─────────────────────────────

    /** 读权限：超管天然全权；其余走账本的 FEDERATION_ADMIN（裁决方视角）。 */
    private boolean mayRead(HttpServletRequest request) {
        if (request.getAttribute(AdminSessionFilter.ROLE_ATTRIBUTE) == AdminRole.SUPER_ADMIN) {
            return true;
        }
        ActorType subjectType = (ActorType) request.getAttribute(AdminSessionFilter.SUBJECT_TYPE_ATTRIBUTE);
        Long subjectId = (Long) request.getAttribute(AdminSessionFilter.SUBJECT_ID_ATTRIBUTE);
        return grants.hasPermission(subjectType, subjectId, PlatformPermission.FEDERATION_ADMIN);
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403)
                .body(Map.of("error", "无担保订单查看权限：当前主体不是超管，且未持有 FEDERATION_ADMIN"));
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    private static boolean isKnownState(String state) {
        for (EscrowOrder.State candidate : EscrowOrder.State.values()) {
            if (candidate.name().equals(state)) {
                return true;
            }
        }
        return false;
    }

    /** 分页入参：页码非负、页长落在 {@code [1, MAX_PAGE_SIZE]}；排序固定为 id 倒序（新在前）。 */
    private static Pageable pageable(int page, int size) {
        int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        return PageRequest.of(Math.max(0, page), boundedSize, Sort.by(Sort.Direction.DESC, "id"));
    }

    /** 分页响应外壳：{@code items} + 页码/页长/总数。 */
    private static Map<String, Object> page(Page<?> result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.getContent());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("total", result.getTotalElements());
        return body;
    }

    /**
     * 订单投影。状态同时给英文枚举与中文名——前端不该自己维护一份状态中文表
     * （否则新增状态时两边必然漂移）。
     */
    private static Map<String, Object> toView(EscrowOrder order) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", order.getId());
        view.put("state", order.getState());
        view.put("stateLabel", com.tg.heyisheng.bot.escrow.EscrowMessages.stateName(order.getState()));
        view.put("buyerUserId", order.getBuyerUserId());
        view.put("sellerUserId", order.getSellerUserId());
        view.put("amount", order.getAmount() == null ? null : order.getAmount().toPlainString());
        view.put("currency", order.getCurrency());
        view.put("reason", order.getReason());
        view.put("createdAt", order.getCreatedAt() == null ? null : order.getCreatedAt().toString());
        view.put("updatedAt", order.getUpdatedAt() == null ? null : order.getUpdatedAt().toString());
        return view;
    }

    /** 供测试与诊断：本控制器暴露的筛选状态全集。 */
    static List<String> knownStates() {
        return List.of(EscrowOrder.State.values()).stream().map(Enum::name).toList();
    }
}
