package com.tg.heyisheng.bot.credit;

import com.tg.heyisheng.bot.common.exception.TggConfigException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 处罚令签名器（模块七）：HMAC-SHA256。
 *
 * <p><b>为什么必须签名</b>：处罚令会被广播到联邦各节点；无签名则任何节点都能伪造他节点的处罚。
 * 启用模块七时若未配置密钥，构造即失败（fail-fast）——不降级放行，
 * 因为"未签名的处罚令"与"可伪造的处罚令"是同一件事。
 *
 * <p><b>验签用常量时间比较</b>（{@link MessageDigest#isEqual}），与 {@code SecretTokenVerifier} 同纪律。
 */
public class PenaltySigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    /**
     * @param signingKey 签名密钥；为空即抛 {@link TggConfigException}（启用模块七的必需配置）
     */
    public PenaltySigner(String signingKey) {
        if (signingKey == null || signingKey.isBlank()) {
            throw new TggConfigException(
                    "启用 tgg.credit 时必须配置 TGG_CREDIT_SIGNING_KEY（否则处罚令无法签名、可被伪造）");
        }
        this.key = signingKey.getBytes(StandardCharsets.UTF_8);
    }

    /** 计算处罚令的签名（十六进制小写）。 */
    public String sign(CreditPenaltyOrder order) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] raw = mac.doFinal(order.canonicalString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", ex);
        }
    }

    /** 计算签名并附加到令上，返回带签名的实例。 */
    public CreditPenaltyOrder signAndAttach(CreditPenaltyOrder unsigned) {
        return unsigned.withSignature(sign(unsigned));
    }

    /**
     * 验证处罚令的签名是否与内容一致。
     *
     * @return true 表示签名有效且内容未被篡改；签名缺失或内容被改则 false
     */
    public boolean verify(CreditPenaltyOrder order) {
        if (order == null || order.signature() == null) {
            return false;
        }
        String expected = sign(order);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                order.signature().getBytes(StandardCharsets.UTF_8));
    }
}
