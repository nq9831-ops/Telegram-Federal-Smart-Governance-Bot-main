package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.exception.TggConfigException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 处罚令签名器（模块七）：<b>Ed25519 非对称签名</b>（持本节点私钥）。
 *
 * <p><b>为什么是 Ed25519 而不是 HMAC（2026-09-17 修正）</b>：HMAC-SHA256 是<b>对称</b>密码——
 * 验签方持有与签名方相同的密钥，因而<b>同样能伪造签名</b>。"每节点独立 HMAC key" 只能带来
 * 隔离性（一个节点泄露不波及他人），**做不到"节点间不可互相伪造"**。Ed25519 下每节点持
 * 私钥签名、对端持**公钥**验签，持公钥者无法伪造。
 *
 * <p><b>密钥格式</b>：私钥为 PKCS#8 DER 的 Base64（{@code TGG_CREDIT_PRIVATE_KEY}）。
 * 缺失或非法即抛 {@link TggConfigException}（fail-fast）——不降级放行。
 *
 * <p>验签见 {@link PenaltyVerifier}（用对端公钥）。
 */
public class PenaltySigner {

    /** 算法名，与 {@link PenaltyVerifier} 共用。 */
    static final String ALGORITHM = "Ed25519";

    private final PrivateKey privateKey;

    /**
     * @param privateKeyBase64 Ed25519 私钥（PKCS#8 DER 的 Base64）；为空即抛 {@link TggConfigException}
     */
    public PenaltySigner(String privateKeyBase64) {
        if (privateKeyBase64 == null || privateKeyBase64.isBlank()) {
            throw new TggConfigException(
                    "启用 tgg.credit 时必须配置 TGG_CREDIT_PRIVATE_KEY（Ed25519 私钥，PKCS#8 Base64；否则处罚令无法签名）");
        }
        this.privateKey = decodePrivateKey(privateKeyBase64);
    }

    /** 计算处罚令的签名（Base64）。 */
    public String sign(CreditPenaltyOrder order) {
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initSign(privateKey);
            sig.update(order.canonicalString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Ed25519 签名失败", ex);
        }
    }

    /** 计算签名并附加到令上，返回带签名的实例。 */
    public CreditPenaltyOrder signAndAttach(CreditPenaltyOrder unsigned) {
        return unsigned.withSignature(sign(unsigned));
    }

    /** 解析 PKCS#8 Base64 私钥；非法即抛 {@link TggConfigException}（配置错误，应拦在启动期）。 */
    static PrivateKey decodePrivateKey(String base64) {
        try {
            byte[] der = Base64.getDecoder().decode(base64.trim());
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new TggConfigException(
                    "TGG_CREDIT_PRIVATE_KEY 不是合法的 Ed25519 PKCS#8 Base64 私钥", ex);
        }
    }
}
