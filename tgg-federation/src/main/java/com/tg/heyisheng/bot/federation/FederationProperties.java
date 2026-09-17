package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.common.exception.TggConfigException;
import com.tg.heyisheng.bot.credit.PenaltyVerifier;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * 模块八配置（前缀 {@code tgg.federation}）。
 *
 * <pre>
 * tgg.federation.enabled  = TGG_FEDERATION_ENABLED（默认 false）
 * tgg.federation.nodes    = TGG_FEDERATION_NODES（对端清单：url|公钥Base64，逗号分隔）
 * tgg.federation.admins   = TGG_FEDERATION_ADMINS（联邦管理员 userId 列表，逗号分隔）
 * </pre>
 *
 * <p><b>为什么没有"本节点私钥"配置</b>：处罚令由**模块七**用自己的
 * {@code TGG_CREDIT_PRIVATE_KEY}（Ed25519）签名，联邦只**转发已签名的令**；
 * 接收方用**签发节点的公钥**（即本清单里对应节点的公钥）验签。
 * 故 {@code TGG_CREDIT_PRIVATE_KEY} 就是"节点签名密钥"，无需第二把。
 */
@ConfigurationProperties(prefix = "tgg.federation")
public class FederationProperties {

    private boolean enabled;
    private String nodes;
    private String admins;

    /**
     * 解析对端节点清单。
     *
     * <p>格式 {@code url|公钥Base64}，多项以逗号分隔。格式错误或公钥非法即抛
     * {@link TggConfigException}（配置错误应拦在启动期，而非运行时静默跳过某个节点）。
     */
    public List<FederationNode> parsedNodes() {
        if (nodes == null || nodes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(nodes.split(","))
                .map(String::trim)
                .filter(spec -> !spec.isEmpty())
                .map(FederationProperties::parseNode)
                .toList();
    }

    /** 解析联邦管理员 id 列表（**全局白名单**，与群内角色无关）。 */
    public List<Long> parsedAdmins() {
        if (admins == null || admins.isBlank()) {
            return List.of();
        }
        return Arrays.stream(admins.split(","))
                .map(String::trim)
                .filter(spec -> !spec.isEmpty())
                .map(spec -> {
                    try {
                        return Long.parseLong(spec);
                    } catch (NumberFormatException ex) {
                        throw new TggConfigException("tgg.federation.admins 含非数字条目：" + spec, ex);
                    }
                })
                .toList();
    }

    private static FederationNode parseNode(String spec) {
        int sep = spec.indexOf('|');
        if (sep <= 0 || sep == spec.length() - 1) {
            throw new TggConfigException(
                    "tgg.federation.nodes 条目格式应为 url|公钥Base64，实为：" + spec);
        }
        String url = spec.substring(0, sep).trim();
        String pubKey = spec.substring(sep + 1).trim();
        try {
            return new FederationNode(url, PenaltyVerifier.parsePublicKey(pubKey));
        } catch (IllegalArgumentException ex) {
            throw new TggConfigException("tgg.federation.nodes 中 " + url + " 的公钥非法", ex);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNodes() {
        return nodes;
    }

    public void setNodes(String nodes) {
        this.nodes = nodes;
    }

    public String getAdmins() {
        return admins;
    }

    public void setAdmins(String admins) {
        this.admins = admins;
    }
}
