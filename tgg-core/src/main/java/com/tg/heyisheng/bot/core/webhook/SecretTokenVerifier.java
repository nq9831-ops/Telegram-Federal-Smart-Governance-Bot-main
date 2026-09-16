package com.tg.heyisheng.bot.core.webhook;

import com.tg.heyisheng.bot.common.exception.TggConfigException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Webhook secret token 校验器。
 *
 * <p>使用 {@link MessageDigest#isEqual} 做<b>常量时间</b>比较，避免按字节短路比较泄露
 * 前缀信息、抵御时序侧信道攻击（V5.0 明确要求「常量时间比较」）。
 *
 * <p><b>为什么必须自己实现</b>：TelegramBots 10.3.0 不校验该请求头
 * （库中 {@code secretToken} 仅作为 {@code SetWebhook} 的出参存在，接收侧零校验）。
 */
public class SecretTokenVerifier {

    private final byte[] expected;

    public SecretTokenVerifier(String expectedSecret) {
        if (expectedSecret == null || expectedSecret.isEmpty()) {
            throw new TggConfigException("Webhook secret 未配置 —— 拒绝以空密钥启动校验器");
        }
        this.expected = expectedSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param provided 请求头中的值，可为 null
     * @return 是否与期望值完全一致
     */
    public boolean verify(String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected);
    }
}
