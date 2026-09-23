package com.tg.heyisheng.bot.admin.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Telegram <b>Mini App</b> {@code initData} 服务端验签（模块十二 · TON 担保交易 · gap-ESC-03）。
 *
 * <p><b>为什么不信任前端传来的身份</b>：Mini App 由 Telegram WebView 打开，{@code initData}
 * （一段 URL query string）里的 {@code user} 是前端可改的 JSON 文本。只有服务端用 bot token
 * 重算 HMAC 并比对，才能证明「这条确实由 Telegram 签发、且未被篡改」。
 *
 * <p><b>算法（外部规范，与本仓 {@link TelegramLoginVerifier} 反向交叉验证）</b>：
 * <pre>
 *   data_check_string = 除 hash 外所有字段（<b>URL 解码后</b>），按 key 字母序 "k=v"，以 \n 连接
 *   secret_key        = HMAC_SHA256(key="WebAppData", message=bot_token)   ← Mini App
 *   hash              = HMAC_SHA256(message=data_check_string, key=secret_key)
 * </pre>
 * ⚠️ <b>密钥与消息的位置与 Login Widget 相反</b>：Widget 用 {@code secret_key = SHA256(bot_token)}
 * （见 {@link TelegramLoginVerifier}）。两者<b>绝不可混用</b>——本类只实现 Mini App 算法，
 * 与 {@code TelegramLoginVerifier} 各自独立，不共享密钥构造。
 * 反向证据已入库：{@code TelegramLoginVerifierTest#miniAppSignedDataDoesNotPassWidgetVerification}（Widget 验不过 MiniApp 签名），
 * 以及本类测试 {@code MiniAppInitDataVerifierTest#widgetSignedInitDataDoesNotPassMiniAppVerification}（MiniApp 验不过 Widget 签名）。
 *
 * <p><b>auth_date 时效</b>：只接受 {@code [now - maxAge, now + 5min]} 窗口内的签名
 * （上限留 5 分钟容忍时钟漂移；过旧的重放一律拒绝）。
 *
 * <p><b>重放拒绝</b>：同一份 {@code initData}（以签名 {@code hash} 为指纹）只接受一次——
 * 否则拦获一条有效 {@code initData} 即可无限次换取会话。指纹取整个签名 payload 而非
 * {@code (id, auth_date)}，因为 Mini App 每次启动带不同的 {@code query_id}（故 hash 不同），
 * 用 payload 指纹可精确拦截「同一条被重放」而不误伤「同一用户相近时间的不同次启动」。
 * nonce 记录在进程内存（有界：过期即清）；多副本部署下每副本各记一份，足以挡住
 * 「同一副本反复重放」这一最常见形态；跨副本的严格一次性需落库（见 KNOWN-ISSUES）。
 *
 * <p><b>会话令牌端点</b>：本类只做验签。<b>交换会话令牌的 HTTP 端点不在本批范围</b>（见交付说明），
 * 后续可复用 {@link AdminSessionFilter} 的会话模型（fail-closed）。
 */
public class MiniAppInitDataVerifier {

    /** 校验通过的主体。 */
    public record MiniAppUser(long userId, String firstName, String username, Instant authDate) {
    }

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final byte[] secretKey;
    private final Duration maxAge;
    /** nonce（签名 hash）→ 失效时刻。 */
    private final Map<String, Instant> usedNonces = new ConcurrentHashMap<>();

    public MiniAppInitDataVerifier(String botToken, Duration maxAge) {
        // bot token 未配置时仍构造本对象（verify 一律返回空、isConfigured 为 false），
        // 便于装配期统一处理「Mini App 登录不可用」而不使整个上下文起不来。
        // 注意：密钥 = HMAC(key="WebAppData", message=bot_token)，与 Widget 的 sha256(bot_token) 相反。
        this.secretKey = (botToken == null || botToken.isBlank())
                ? null
                : hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken);
        this.maxAge = maxAge;
    }

    /** 是否已配置 bot token（未配置则 Mini App 登录不可用）。 */
    public boolean isConfigured() {
        return secretKey != null;
    }

    /**
     * 校验 Mini App {@code initData}。
     *
     * @param initData Telegram WebView 传入的原始 query string（未解码）
     * @return 通过时含 TG userId；任一环节不符返回空
     */
    public Optional<MiniAppUser> verify(String initData, Instant now) {
        if (secretKey == null) {
            return Optional.empty();
        }
        if (initData == null || initData.isBlank()) {
            return Optional.empty();
        }

        Map<String, String> fields = parse(initData);
        String hash = fields.get("hash");
        String authDateRaw = fields.get("auth_date");
        if (hash == null || authDateRaw == null) {
            return Optional.empty();
        }

        String expected = hex(hmac(secretKey, dataCheckString(fields)));
        if (!constantTimeEquals(expected, hash)) {
            return Optional.empty();
        }

        long authEpoch;
        try {
            authEpoch = Long.parseLong(authDateRaw.trim());
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        Instant authDate = Instant.ofEpochSecond(authEpoch);
        if (authDate.isBefore(now.minus(maxAge)) || authDate.isAfter(now.plus(CLOCK_SKEW))) {
            return Optional.empty();
        }

        // 只有验签通过后才解析不可信的 user JSON。
        MiniAppUser user = parseUser(fields.get("user"), authDate);
        if (user == null) {
            return Optional.empty();
        }

        // 重放拒绝：同一签名 payload 只接受一次
        purgeExpired(now);
        if (usedNonces.putIfAbsent(hash, authDate.plus(maxAge)) != null) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    /** 解析 {@code user} 字段 JSON；缺 id 或格式非法返回 {@code null}。 */
    private static MiniAppUser parseUser(String userJson, Instant authDate) {
        if (userJson == null || userJson.isBlank()) {
            return null;
        }
        try {
            JsonNode user = JSON.readTree(userJson);
            if (!user.hasNonNull("id")) {
                return null;
            }
            long userId = user.get("id").asLong();
            String firstName = user.hasNonNull("first_name") ? user.get("first_name").asText() : null;
            String username = user.hasNonNull("username") ? user.get("username").asText() : null;
            return new MiniAppUser(userId, firstName, username, authDate);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 把原始 query string 解析为「URL 解码后」的字段表（同名键后出现者覆盖）。 */
    static Map<String, String> parse(String initData) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : initData.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            fields.put(key, value);
        }
        return fields;
    }

    /** 除 {@code hash} 外所有字段，按 key 字母序 "k=v"，以 {@code \n} 连接。 */
    static String dataCheckString(Map<String, String> fields) {
        return fields.entrySet().stream()
                .filter(e -> !"hash".equals(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
    }

    private void purgeExpired(Instant now) {
        usedNonces.entrySet().removeIf(e -> e.getValue().isBefore(now));
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
