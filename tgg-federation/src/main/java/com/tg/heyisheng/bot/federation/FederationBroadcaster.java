package com.tg.heyisheng.bot.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.credit.CreditPenaltyOrder;
import com.tg.heyisheng.bot.credit.PenaltyOrderPublisher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 出站广播器（模块八）：把本节点产出的处罚令推送给联邦各对端节点。
 *
 * <p>它以 {@code @Primary} 覆盖模块七留的 {@code PenaltyOrderPublisher.noop()}——
 * 这正是模块七"处罚令暂无真实消费方"缺口的补位。
 *
 * <p><b>尽力而为</b>：单个节点失败只记日志、继续下一个；任何异常都在此被吞掉
 * （{@link PenaltyOrderPublisher} 的契约）——联邦广播失败**不得**回滚本地记账。
 *
 * <p><b>不发送本节点标识</b>：接收方靠"谁的公钥能验通签名"来判定来源，
 * 故本端无需声明自己是谁（也就无从伪造）。
 */
public class FederationBroadcaster implements PenaltyOrderPublisher {

    private static final Logger log = LoggerFactory.getLogger(FederationBroadcaster.class);
    private static final MediaType JSON = MediaType.parse("application/json");

    private final List<FederationNode> nodes;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FederationBroadcaster(List<FederationNode> nodes,
                                 OkHttpClient httpClient,
                                 ObjectMapper objectMapper) {
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(CreditPenaltyOrder order) {
        if (order == null) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(order);
        } catch (Exception ex) {
            log.error("序列化处罚令失败，本次不广播：orderId={}", order.orderId(), ex);
            return;
        }
        for (FederationNode node : nodes) {
            broadcastTo(node, payload, order.orderId());
        }
    }

    private void broadcastTo(FederationNode node, String payload, String orderId) {
        try {
            Request request = new Request.Builder()
                    .url(node.penaltyEndpoint())
                    .post(RequestBody.create(payload, JSON))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("向联邦节点 {} 广播处罚令失败：HTTP {}（orderId={}）",
                            node.baseUrl(), response.code(), orderId);
                }
            }
        } catch (Exception ex) {
            // 单点失败不影响其他节点，也不上抛——联邦广播是尽力而为
            log.warn("向联邦节点 {} 广播处罚令异常（已跳过该节点）：orderId={}",
                    node.baseUrl(), orderId, ex);
        }
    }
}
