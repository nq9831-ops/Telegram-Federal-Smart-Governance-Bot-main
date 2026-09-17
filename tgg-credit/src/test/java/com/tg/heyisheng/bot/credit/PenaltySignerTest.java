package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.exception.TggConfigException;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 处罚令签名的单测：确定性 + 防篡改 + 缺密钥即拒绝。
 *
 * <p>断言的是<b>签名与验签的真实结果</b>，不是"方法跑过了"。
 */
class PenaltySignerTest {

    private static final String KEY = "test-signing-key";

    private static CreditPenaltyOrder order(PenaltyType type, long subjectId) {
        return new CreditPenaltyOrder("order-1", CreditSubjectType.INDIVIDUAL, subjectId,
                type, Instant.ofEpochMilli(1_700_000_000_000L), null);
    }

    @Test
    void sameInputProducesSameSignature() {
        PenaltySigner signer = new PenaltySigner(KEY);

        String first = signer.sign(order(PenaltyType.WARN, 42L));
        String second = signer.sign(order(PenaltyType.WARN, 42L));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void signedOrderVerifies() {
        PenaltySigner signer = new PenaltySigner(KEY);
        CreditPenaltyOrder signed = signer.signAndAttach(order(PenaltyType.MUTE, 42L));

        assertThat(signed.signature()).isNotBlank();
        assertThat(signer.verify(signed)).isTrue();
    }

    @Test
    void tamperedPenaltyTypeFailsVerification() {
        PenaltySigner signer = new PenaltySigner(KEY);
        CreditPenaltyOrder signed = signer.signAndAttach(order(PenaltyType.WARN, 42L));

        // 篡改处罚档位（把警告偷偷升级/降级）——签名必须对不上
        CreditPenaltyOrder tampered = new CreditPenaltyOrder(
                signed.orderId(), signed.subjectType(), signed.subjectId(),
                PenaltyType.REPORT_TO_FEDERATION, signed.issuedAt(), signed.signature());

        assertThat(signer.verify(tampered)).isFalse();
    }

    @Test
    void tamperedSubjectIdFailsVerification() {
        PenaltySigner signer = new PenaltySigner(KEY);
        CreditPenaltyOrder signed = signer.signAndAttach(order(PenaltyType.WARN, 42L));

        CreditPenaltyOrder tampered = new CreditPenaltyOrder(
                signed.orderId(), signed.subjectType(), 43L,
                signed.penaltyType(), signed.issuedAt(), signed.signature());

        assertThat(signer.verify(tampered)).isFalse();
    }

    @Test
    void differentKeysDoNotVerifyEachOther() {
        CreditPenaltyOrder signed = new PenaltySigner("key-a").signAndAttach(order(PenaltyType.WARN, 42L));

        assertThat(new PenaltySigner("key-b").verify(signed))
                .as("别的密钥签出来的令，本密钥不得认可")
                .isFalse();
    }

    @Test
    void blankKeyIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new PenaltySigner("  "))
                .isInstanceOf(TggConfigException.class);
        assertThatThrownBy(() -> new PenaltySigner(null))
                .isInstanceOf(TggConfigException.class);
    }
}
