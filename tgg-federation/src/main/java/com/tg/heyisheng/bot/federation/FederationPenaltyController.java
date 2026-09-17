package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.credit.CreditPenaltyOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 联邦处罚令入站端点（模块八）。
 *
 * <pre>
 * POST /federation/penalty
 * Body: CreditPenaltyOrder JSON（含 signature）
 * 200 → {status: accepted|duplicate}
 * 403 → {status: REJECTED_BAD_SIGNATURE}（验签失败/不在白名单，且不落库）
 * </pre>
 *
 * <p><b>为什么端点自验签</b>：{@code SecretTokenFilter} 只挂 {@code /webhook}（精确匹配），
 * 不覆盖本路径；故签名校验由本端点自行完成（见 {@link FederationPenaltyService}）。
 *
 * <p><b>路径不冲突</b>：库的 {@code POST /{botPath}} 是单段路径（{@code /webhook}），
 * 本端点两段，Spring MVC 不会混淆。
 */
@RestController
@ConditionalOnProperty(prefix = "tgg.federation", name = "enabled", havingValue = "true")
public class FederationPenaltyController {

    private final FederationPenaltyService service;

    public FederationPenaltyController(FederationPenaltyService service) {
        this.service = service;
    }

    @PostMapping(path = "/federation/penalty",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> receive(@RequestBody CreditPenaltyOrder order) {
        FederationIngestResult result = service.ingest(order);
        return switch (result) {
            case ACCEPTED -> ResponseEntity.ok(Map.of("status", "accepted"));
            case DUPLICATE -> ResponseEntity.ok(Map.of("status", "duplicate"));
            default -> ResponseEntity.status(403).body(Map.of("status", result.name()));
        };
    }
}
