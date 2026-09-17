package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.exception.TggConfigException;
import com.tg.heyisheng.bot.core.credit.CreditSubjectType;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ed25519 处罚令签名的单测：确定性 + 防篡改 + <b>跨节点不可伪造</b>。
 *
 * <p>最后一条是"每节点独立密钥"的核心断言——若有人把实现退回 HMAC（对称），
 * {@link #signatureFromAnotherNodeDoesNotVerify()} 会立刻变红。
 */
class PenaltySignerTest {

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

    private static String pub(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    private static CreditPenaltyOrder order(PenaltyType type, long subjectId) {
        return new CreditPenaltyOrder("order-1", CreditSubjectType.INDIVIDUAL, subjectId,
                type, Instant.ofEpochMilli(1_700_000_000_000L), null);
    }

    @Test
    void sameInputProducesSameSignature() {
        PenaltySigner signer = new PenaltySigner(priv(NODE_A));

        assertThat(signer.sign(order(PenaltyType.WARN, 42L)))
                .isEqualTo(signer.sign(order(PenaltyType.WARN, 42L)));
    }

    @Test
    void signedOrderVerifiesWithOwnPublicKey() {
        PenaltySigner signer = new PenaltySigner(priv(NODE_A));
        CreditPenaltyOrder signed = signer.signAndAttach(order(PenaltyType.MUTE, 42L));

        assertThat(signed.signature()).isNotBlank();
        assertThat(PenaltyVerifier.verify(signed, NODE_A.getPublic())).isTrue();
        assertThat(PenaltyVerifier.verify(signed, PenaltyVerifier.parsePublicKey(pub(NODE_A)))).isTrue();
    }

    @Test
    void signatureFromAnotherNodeDoesNotVerify() {
        // 核心：B 私钥签的令，用 A 公钥验签**必须失败**——持公钥无法伪造签名
        PenaltySigner nodeBSigner = new PenaltySigner(priv(NODE_B));
        CreditPenaltyOrder signedByB = nodeBSigner.signAndAttach(order(PenaltyType.WARN, 42L));

        assertThat(PenaltyVerifier.verify(signedByB, NODE_A.getPublic()))
                .as("持 A 公钥不得认可 B 签的令——这正是'每节点独立密钥'的核心")
                .isFalse();
        assertThat(PenaltyVerifier.verify(signedByB, NODE_B.getPublic())).isTrue();
    }

    @Test
    void tamperedPenaltyTypeFailsVerification() {
        PenaltySigner signer = new PenaltySigner(priv(NODE_A));
        CreditPenaltyOrder signed = signer.signAndAttach(order(PenaltyType.WARN, 42L));

        CreditPenaltyOrder tampered = new CreditPenaltyOrder(
                signed.orderId(), signed.subjectType(), signed.subjectId(),
                PenaltyType.REPORT_TO_FEDERATION, signed.issuedAt(), signed.signature());

        assertThat(PenaltyVerifier.verify(tampered, NODE_A.getPublic())).isFalse();
    }

    @Test
    void tamperedSubjectIdFailsVerification() {
        PenaltySigner signer = new PenaltySigner(priv(NODE_A));
        CreditPenaltyOrder signed = signer.signAndAttach(order(PenaltyType.WARN, 42L));

        CreditPenaltyOrder tampered = new CreditPenaltyOrder(
                signed.orderId(), signed.subjectType(), 43L,
                signed.penaltyType(), signed.issuedAt(), signed.signature());

        assertThat(PenaltyVerifier.verify(tampered, NODE_A.getPublic())).isFalse();
    }

    @Test
    void missingSignatureFailsVerification() {
        assertThat(PenaltyVerifier.verify(order(PenaltyType.WARN, 42L), NODE_A.getPublic())).isFalse();
    }

    @Test
    void blankOrInvalidPrivateKeyIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new PenaltySigner("  "))
                .isInstanceOf(TggConfigException.class);
        assertThatThrownBy(() -> new PenaltySigner(null))
                .isInstanceOf(TggConfigException.class);
        assertThatThrownBy(() -> new PenaltySigner("not-a-valid-pkcs8-key"))
                .isInstanceOf(TggConfigException.class);
    }

    @Test
    void invalidPublicKeyIsRejectedByParser() {
        assertThatThrownBy(() -> PenaltyVerifier.parsePublicKey("garbage"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
