package com.tg.heyisheng.bot.credit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 处罚令验签器（Ed25519，持<b>对端公钥</b>）。
 *
 * <p>联邦节点收到他节点的处罚令时，用**来源节点的公钥**验证签名。
 * 与 {@link PenaltySigner} 分离是刻意的：签名方只需私钥、验签方只需公钥，
 * 两者持有的材料不同——这正是"每节点独立、互不可伪造"的实现基础。
 *
 * <p>公钥格式：X.509 DER 的 Base64。
 */
public final class PenaltyVerifier {

    private PenaltyVerifier() {
    }

    /**
     * 解析 X.509 Base64 公钥。
     *
     * @throws IllegalArgumentException 非法公钥（由调用方决定拒绝策略：联邦入站应据此拒绝）
     */
    public static PublicKey parsePublicKey(String base64) {
        try {
            byte[] der = Base64.getDecoder().decode(base64.trim());
            return KeyFactory.getInstance(PenaltySigner.ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("不是合法的 Ed25519 X.509 Base64 公钥", ex);
        }
    }

    /**
     * 用给定公钥验证处罚令签名。
     *
     * @return true 仅当签名有效且内容未被篡改；签名缺失、内容被改、或公钥不符一律 false
     */
    public static boolean verify(CreditPenaltyOrder order, PublicKey publicKey) {
        if (order == null || order.signature() == null || publicKey == null) {
            return false;
        }
        try {
            Signature sig = Signature.getInstance(PenaltySigner.ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(order.canonicalString().getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(order.signature()));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            // 签名不是合法 Base64、或长度不符——一律视为验签失败，不抛
            return false;
        }
    }
}
