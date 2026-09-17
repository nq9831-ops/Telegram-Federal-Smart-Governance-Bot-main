package com.tg.heyisheng.bot.federation;

import java.security.PublicKey;
import java.util.Objects;

/**
 * 一个对端联邦节点：基址 + 它的 Ed25519 公钥。
 *
 * <p>公钥用于**验签**该节点广播来的处罚令——持公钥无法伪造其签名（这正是
 * "每节点独立密钥"与 HMAC 单密钥的本质区别）。
 *
 * @param baseUrl   节点基址（如 {@code https://a.example.com}）
 * @param publicKey 该节点的 Ed25519 公钥
 */
public record FederationNode(String baseUrl, PublicKey publicKey) {

    public FederationNode {
        Objects.requireNonNull(baseUrl, "baseUrl 不可为空");
        Objects.requireNonNull(publicKey, "publicKey 不可为空");
    }

    /** 规范化基址：去掉尾部斜杠，便于拼接路径。 */
    public String normalizedBaseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 该节点的处罚令接收端点。 */
    public String penaltyEndpoint() {
        return normalizedBaseUrl() + "/federation/penalty";
    }
}
