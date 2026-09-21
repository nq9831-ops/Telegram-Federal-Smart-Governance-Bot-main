package com.tg.heyisheng.bot.admin.identity;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telegram 登录验签（模块十一 · TG 用户登录）。
 *
 * <p><b>本类存在的首要理由</b>：Login Widget 与 MiniApp 的 {@code secret_key} 构造<b>不同</b>
 * （前者 {@code SHA256(bot_token)}，后者 {@code HMAC(key="WebAppData", message=bot_token)}）——
 * 写错一个就成漏洞。故用「MiniApp 签名的数据必须验不过 Widget 验签器」这条<b>反向</b>用例钉死差异。
 *
 * <p>正/反向覆盖：正确通过 / 篡改任一字段失败 / 过期失败 / 未来时间超容忍失败 / 重放拒绝。
 */
class TelegramLoginVerifierTest {

    private static final String TOKEN = "123456:TEST-TOKEN-abcdefghijklmnop";
    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");
    private static final Duration MAX_AGE = Duration.ofHours(1);

    private final TelegramLoginVerifier verifier = new TelegramLoginVerifier(TOKEN, MAX_AGE);

    /** 用 Login Widget 算法签一条数据。 */
    private static Map<String, String> widgetSigned(long userId, long authEpoch) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(userId));
        data.put("first_name", "Test");
        data.put("username", "testuser");
        data.put("auth_date", String.valueOf(authEpoch));
        data.put("hash", hex(hmac(sha256(TOKEN), TelegramLoginVerifier.dataCheckString(data))));
        return data;
    }

    /** 用 <b>MiniApp</b> 算法签一条数据（secret_key 的密钥与消息与 Widget 相反）。 */
    private static Map<String, String> miniAppSigned(long userId, long authEpoch) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(userId));
        data.put("first_name", "Test");
        data.put("auth_date", String.valueOf(authEpoch));
        byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), TOKEN);
        data.put("hash", hex(hmac(secret, TelegramLoginVerifier.dataCheckString(data))));
        return data;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    void validWidgetDataPasses() {
        Map<String, String> data = widgetSigned(42L, NOW.getEpochSecond() - 10);

        Optional<TelegramLoginVerifier.TelegramUser> result = verifier.verify(data, NOW);

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(42L);
    }

    @Test
    void tamperedFieldFails() {
        Map<String, String> data = widgetSigned(42L, NOW.getEpochSecond() - 10);
        data.put("username", "hacker");   // 篡改任一字段 → hash 对不上

        assertThat(verifier.verify(data, NOW)).isEmpty();
    }

    @Test
    void wrongHashFails() {
        Map<String, String> data = widgetSigned(42L, NOW.getEpochSecond() - 10);
        data.put("hash", "0".repeat(64));

        assertThat(verifier.verify(data, NOW)).isEmpty();
    }

    @Test
    void expiredAuthDateFails() {
        Map<String, String> data = widgetSigned(42L, NOW.minus(Duration.ofHours(2)).getEpochSecond());

        assertThat(verifier.verify(data, NOW)).isEmpty();
    }

    @Test
    void futureAuthDateBeyondSkewFails() {
        Map<String, String> data = widgetSigned(42L, NOW.plus(Duration.ofMinutes(30)).getEpochSecond());

        assertThat(verifier.verify(data, NOW)).isEmpty();
    }

    /**
     * <b>关键反向用例</b>：MiniApp 签名的数据用 Widget 验签器必须失败——
     * 若两算法被写成同一个，这条会通过（漏洞）。
     */
    @Test
    void miniAppSignedDataDoesNotPassWidgetVerification() {
        Map<String, String> mini = miniAppSigned(42L, NOW.getEpochSecond() - 10);

        assertThat(verifier.verify(mini, NOW))
                .as("MiniApp 的 secret_key 与 Widget 不同，绝不可互相验通")
                .isEmpty();
    }

    @Test
    void replayedPayloadIsRejected() {
        Map<String, String> data = widgetSigned(42L, NOW.getEpochSecond() - 10);

        assertThat(verifier.verify(data, NOW)).isPresent();
        assertThat(verifier.verify(data, NOW)).as("同一 (id, auth_date) 第二次必须被拒").isEmpty();
    }

    @Test
    void missingFieldsFail() {
        assertThat(verifier.verify(null, NOW)).isEmpty();
        assertThat(verifier.verify(Map.of("id", "42"), NOW)).isEmpty();
    }
}
