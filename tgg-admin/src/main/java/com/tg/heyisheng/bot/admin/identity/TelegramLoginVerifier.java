package com.tg.heyisheng.bot.admin.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Telegram <b>Login Widget</b> 服务端验签（模块十一 · TG 用户登录）。
 *
 * <p><b>为什么不信任前端传来的身份</b>：widget 回调里的 {@code id}/{@code first_name}/{@code hash}
 * 全是前端可造的字符串。只有服务端用 bot token 重算 HMAC 并比对，才能证明「这条确实来自 Telegram」。
 *
 * <p><b>算法（外部规范，已用双实现交叉验证）</b>：
 * <pre>
 *   data_check_string = 除 hash 外所有字段，按 key 字母序 "k=v"，以 \n 连接
 *   secret_key        = SHA256(bot_token)                       ← Login Widget
 *   hash              = HMAC_SHA256(data_check_string, secret_key)
 * </pre>
 * ⚠️ <b>MiniApp initData 的 secret_key 不同</b>：{@code HMAC_SHA256(key="WebAppData", message=bot_token)}
 * ——密钥与消息与上式<b>相反</b>。两者绝不可混用；本类只实现 Widget 算法。
 * 交叉验证证据见 {@code TelegramLoginVerifierTest}（用 Widget 算法验 MiniApp 签名的数据必须失败）。
 *
 * <p><b>auth_date 时效</b>：只接受 {@code [now - maxAge, now + 5min]} 窗口内的签名
 * （上限留 5 分钟容忍时钟漂移；过旧的重放一律拒绝）。
 *
 * <p><b>重放拒绝</b>：同一 {@code (id, auth_date)} 只接受一次——否则拦获一条有效回调即可无限次登录。
 * 已用 nonce 记录在内存（进程内、有界：过期即清）。多副本部署下每个副本各记一份，
 * 足以挡住「同一副本反复重放」这一最常见形态；跨副本的严格一次性需落库（见 KNOWN-ISSUES）。
 */
public class TelegramLoginVerifier {

    /** 校验通过的主体。 */
    public record TelegramUser(long userId, String firstName, String username, Instant authDate) {
    }

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final byte[] secretKey;
    private final Duration maxAge;
    /** nonce（{@code id:auth_date}）→ 失效时刻。 */
    private final Map<String, Instant> usedNonces = new ConcurrentHashMap<>();

    public TelegramLoginVerifier(String botToken, Duration maxAge) {
        // bot token 未配置时仍构造本对象（verify 一律返回空、isConfigured 为 false），
        // 便于装配期统一处理「TG 登录不可用」而不使整个上下文起不来。
        this.secretKey = (botToken == null || botToken.isBlank()) ? null : sha256(botToken);
        this.maxAge = maxAge;
    }

    /** 是否已配置 bot token（未配置则 TG 登录不可用）。 */
    public boolean isConfigured() {
        return secretKey != null;
    }

    /**
     * 校验 widget 回调数据。
     *
     * @return 通过时含 TG userId；任一环节不符返回空
     */
    public Optional<TelegramUser> verify(Map<String, String> data, Instant now) {
        if (secretKey == null) {
            return Optional.empty();
        }
        if (data == null || data.get("hash") == null
                || data.get("id") == null || data.get("auth_date") == null) {
            return Optional.empty();
        }
        String expected = hex(hmac(secretKey, dataCheckString(data)));
        if (!constantTimeEquals(expected, data.get("hash"))) {
            return Optional.empty();
        }

        long authEpoch;
        long userId;
        try {
            authEpoch = Long.parseLong(data.get("auth_date").trim());
            userId = Long.parseLong(data.get("id").trim());
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        Instant authDate = Instant.ofEpochSecond(authEpoch);
        if (authDate.isBefore(now.minus(maxAge)) || authDate.isAfter(now.plus(CLOCK_SKEW))) {
            return Optional.empty();
        }

        // 重放拒绝：同一 (id, auth_date) 只接受一次
        String nonce = userId + ":" + authEpoch;
        purgeExpired(now);
        if (usedNonces.putIfAbsent(nonce, authDate.plus(maxAge)) != null) {
            return Optional.empty();
        }
        return Optional.of(new TelegramUser(userId, data.get("first_name"),
                data.get("username"), authDate));
    }

    /** 除 {@code hash} 外所有字段，按 key 字母序 "k=v"，以 {@code \n} 连接。 */
    static String dataCheckString(Map<String, String> data) {
        return data.entrySet().stream()
                .filter(e -> !"hash".equals(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
    }

    private void purgeExpired(Instant now) {
        usedNonces.entrySet().removeIf(e -> e.getValue().isBefore(now));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 不可用", ex);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
