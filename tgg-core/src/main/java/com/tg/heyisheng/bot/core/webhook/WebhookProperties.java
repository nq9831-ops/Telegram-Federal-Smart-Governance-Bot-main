package com.tg.heyisheng.bot.core.webhook;

import com.tg.heyisheng.bot.common.exception.TggConfigException;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Webhook 相关配置。
 *
 * <p>绑定前缀 {@code tgg.webhook}，对应环境变量：
 * <ul>
 *   <li>{@code TGG_WEBHOOK_SECRET} → {@code tgg.webhook.secret}（必填）</li>
 *   <li>{@code TGG_BOT_TOKEN} → {@code tgg.webhook.bot-token}（必填）</li>
 *   <li>{@code TGG_WEBHOOK_PATH} → {@code tgg.webhook.path}（默认 {@code /webhook}）</li>
 * </ul>
 *
 * <p><b>fail-fast</b>：缺少 secret 时启动即失败，绝不带着"无校验"的默认值运行。
 */
@ConfigurationProperties(prefix = "tgg.webhook")
public class WebhookProperties {

    /** Webhook 路径。库的控制器映射为 {@code POST /{botPath}}，故此处不含前导斜杠语义冲突。 */
    private String path = "/webhook";

    /** Telegram 要求的 secret token。 */
    private String secret;

    /** Bot token（用于 SetWebhook / 发送消息）。 */
    private String botToken;

    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new TggConfigException("TGG_WEBHOOK_SECRET 未配置 —— 拒绝以无校验状态启动");
        }
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getBotToken() {
        return botToken;
    }

    public void setBotToken(String botToken) {
        this.botToken = botToken;
    }
}
