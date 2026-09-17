package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.credit.CreditPenaltyOrder;
import com.tg.heyisheng.bot.credit.PenaltySigner;
import com.tg.heyisheng.bot.credit.PenaltyType;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入站处罚令处理的单测：验签 / 白名单 / 去重 / 拒绝即不落库。
 *
 * <p>用**真实 Ed25519 密钥对**（而非 mock 签名器）——验签是本模块的安全核心，
 * 必须用真实密码学跑通。
 */
class FederationPenaltyServiceTest {

    private static final KeyPair NODE_A = keyPair();
    private static final KeyPair NODE_B = keyPair();

    private static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static String priv(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
    }

    private static CreditPenaltyOrder order(long subjectId) {
        return new CreditPenaltyOrder("order-1", CreditSubjectType.INDIVIDUAL, subjectId,
                PenaltyType.REPORT_TO_FEDERATION, Instant.ofEpochMilli(1_700_000_000_000L), null);
    }

    private final FederationPenaltyRepository repository = mock(FederationPenaltyRepository.class);
    private final List<CreditPenaltyOrder> executed = new ArrayList<>();

    /** 白名单只有节点 A。 */
    private final FederationPenaltyService service = new FederationPenaltyService(
            List.of(new FederationNode("https://a.example.com", NODE_A.getPublic())),
            repository,
            executed::add);

    private void stubInsert(int affectedRows) {
        when(repository.insertIgnore(anyString(), anyString(), anyLong(), anyString(),
                any(Instant.class), any(), anyString(), any(Instant.class))).thenReturn(affectedRows);
    }

    @Test
    void acceptsProperlySignedOrderAndTriggersLocalExecution() {
        stubInsert(1);
        CreditPenaltyOrder signed = new PenaltySigner(priv(NODE_A)).signAndAttach(order(42L));

        assertThat(service.ingest(signed)).isEqualTo(FederationIngestResult.ACCEPTED);
        assertThat(executed).as("接受后须触发本地执行").hasSize(1);
    }

    @Test
    void rejectsSignatureProducedByNonWhitelistedNode() {
        // B 私钥签的令；白名单里只有 A → 无任何公钥可验通 → 拒绝且不落库
        CreditPenaltyOrder signedByB = new PenaltySigner(priv(NODE_B)).signAndAttach(order(42L));

        assertThat(service.ingest(signedByB)).isEqualTo(FederationIngestResult.REJECTED_BAD_SIGNATURE);
        verify(repository, never()).insertIgnore(anyString(), anyString(), anyLong(), anyString(),
                any(Instant.class), any(), anyString(), any(Instant.class));
        assertThat(executed).isEmpty();
    }

    @Test
    void rejectsTamperedOrderEvenWithValidSignatureShape() {
        CreditPenaltyOrder signed = new PenaltySigner(priv(NODE_A)).signAndAttach(order(42L));
        // 篡改主体 id，保留原签名
        CreditPenaltyOrder tampered = new CreditPenaltyOrder(
                signed.orderId(), signed.subjectType(), 999L,
                signed.penaltyType(), signed.issuedAt(), signed.signature());

        assertThat(service.ingest(tampered)).isEqualTo(FederationIngestResult.REJECTED_BAD_SIGNATURE);
        assertThat(executed).isEmpty();
    }

    @Test
    void unsignedOrderIsRejected() {
        assertThat(service.ingest(order(42L))).isEqualTo(FederationIngestResult.REJECTED_BAD_SIGNATURE);
        assertThat(executed).isEmpty();
    }

    @Test
    void duplicateIsIdempotentAndNotExecutedTwice() {
        stubInsert(0); // 数据库层面已存在 → INSERT IGNORE 影响 0 行
        CreditPenaltyOrder signed = new PenaltySigner(priv(NODE_A)).signAndAttach(order(42L));

        assertThat(service.ingest(signed)).isEqualTo(FederationIngestResult.DUPLICATE);
        assertThat(executed).as("重复令不得再次执行本地封禁").isEmpty();
    }
}
