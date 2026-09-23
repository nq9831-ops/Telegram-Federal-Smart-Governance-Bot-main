package com.tg.heyisheng.bot.admin.identity;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
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
 * Mini App {@code initData} 验签（模块十二 · gap-ESC-03）。
 *
 * <p><b>本类存在的首要理由</b>：Mini App 与 Login Widget 的 {@code secret_key} 构造<b>相反</b>
 * （前者 {@code HMAC(key="WebAppData", message=bot_token)}，后者 {@code SHA256(bot_token)}）——
 * 写错一个就成漏洞。故用「Widget 签名的数据必须验不过 MiniApp 验签器」这条<b>反向</b>用例钉死差异
 * （与 {@code TelegramLoginVerifierTest#miniAppSignedDataDoesNotPassWidgetVerification} 互为镜像）。
 *
 * <p>正/反向覆盖：正确通过 + 解析出 userId / 篡改字段失败 / 坏 JSON 失败 / 字段缺失失败 /
 * 过期失败 / 未来超容忍失败 / 重放拒绝 / 未配置 bot token 返空不抛。
 */
class MiniAppInitDataVerifierTest {

    private static final String TOKEN = "123456:TEST-TOKEN-abcdefghijklmnop";
    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");
    private static final Duration MAX_AGE = Duration.ofHours(1);

    private final MiniAppInitDataVerifier verifier = new MiniAppInitDataVerifier(TOKEN, MAX_AGE);

    // ---- 测试侧独立复算随机/验签算法（不复用被测类的 hmac，避免自证） ----

    /** 用 Mini App 算法签一条 initData。 */
    private static String miniAppInitData(long userId, long authEpoch, String queryId) {
        return initData(fields(userId, authEpoch, queryId), miniAppKey());
    }

    /** 用 Login Widget 算法签一条 initData（secret_key 与 Mini App 相反）。 */
    private static String widgetInitData(long userId, long authEpoch, String queryId) {
        return initData(fields(userId, authEpoch, queryId), widgetKey());
    }

    private static Map<String, String> fields(long userId, long authEpoch, String queryId) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("auth_date", String.valueOf(authEpoch));
        f.put("query_id", queryId);
        f.put("user", "{\"id\":" + userId + ",\"first_name\":\"Test\",\"username\":\"testuser\"}");
        return f;
    }

    private static String initData(Map<String, String> fields, byte[] key) {
        return raw(fields, hash(fields, key));
    }

    private static String raw(Map<String, String> fields, String hash) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            sb.append(e.getKey()).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).append('&');
        }
        return sb.append("hash=").append(hash).toString();
    }

    private static String hash(Map<String, String> fields, byte[] key) {
        return hex(hmac(key, MiniAppInitDataVerifier.dataCheckString(fields)));
    }

    /** Mini App：secret_key = HMAC(key="WebAppData", message=bot_token)。 */
    private static byte[] miniAppKey() {
        return hmac("WebAppData".getBytes(StandardCharsets.UTF_8), TOKEN);
    }

    /** Login Widget：secret_key = SHA256(bot_token)。 */
    private static byte[] widgetKey() {
        return sha256(TOKEN);
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

    // ---- 用例 ----

    @Test
    void validInitDataPassesAndParsesUserId() {
        String initData = miniAppInitData(42L, NOW.getEpochSecond() - 10, "qid-1");

        Optional<MiniAppInitDataVerifier.MiniAppUser> result = verifier.verify(initData, NOW);

        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo(42L);
        assertThat(result.get().firstName()).isEqualTo("Test");
        assertThat(result.get().username()).isEqualTo("testuser");
        assertThat(result.get().authDate()).isEqualTo(Instant.ofEpochSecond(NOW.getEpochSecond() - 10));
    }

    @Test
    void authDateWithinClockSkewPasses() {
        String initData = miniAppInitData(42L, NOW.getEpochSecond(), "qid-1");

        assertThat(verifier.verify(initData, NOW)).isPresent();
    }

    /**
     * <b>关键反向用例</b>：Widget 签名的数据用 MiniApp 验签器必须失败——
     * 若两算法被写成同一个，这条会通过（漏洞）。
     */
    @Test
    void widgetSignedInitDataDoesNotPassMiniAppVerification() {
        String widget = widgetInitData(42L, NOW.getEpochSecond() - 10, "qid-1");

        assertThat(verifier.verify(widget, NOW))
                .as("Widget 的 secret_key = SHA256(bot_token)，与 MiniApp 相反，绝不可互相验通")
                .isEmpty();
    }

    @Test
    void tamperedUserFieldFails() {
        Map<String, String> fields = fields(42L, NOW.getEpochSecond() - 10, "qid-1");
        String hash = hash(fields, miniAppKey());
        fields.put("user", "{\"id\":9999,\"first_name\":\"Hacker\"}");   // 签名后篡改 → hash 对不上

        assertThat(verifier.verify(raw(fields, hash), NOW)).isEmpty();
    }

    @Test
    void malformedUserJsonFails() {
        Map<String, String> fields = fields(42L, NOW.getEpochSecond() - 10, "qid-1");
        fields.put("user", "not-a-json");

        assertThat(verifier.verify(initData(fields, miniAppKey()), NOW)).isEmpty();
    }

    @Test
    void expiredAuthDateFails() {
        String initData = miniAppInitData(42L, NOW.minus(Duration.ofHours(2)).getEpochSecond(), "qid-1");

        assertThat(verifier.verify(initData, NOW)).isEmpty();
    }

    @Test
    void futureAuthDateBeyondSkewFails() {
        String initData = miniAppInitData(42L, NOW.plus(Duration.ofMinutes(30)).getEpochSecond(), "qid-1");

        assertThat(verifier.verify(initData, NOW)).isEmpty();
    }

    @Test
    void replayedInitDataIsRejected() {
        String initData = miniAppInitData(42L, NOW.getEpochSecond() - 10, "qid-1");

        assertThat(verifier.verify(initData, NOW)).isPresent();
        assertThat(verifier.verify(initData, NOW)).as("同一条签名 payload 第二次必须被拒").isEmpty();
    }

    /** nonce 取签名 payload（hash）指纹：同用户同时刻的不同次启动（query_id 不同）都应放行。 */
    @Test
    void distinctLaunchesFromSameUserAreBothAccepted() {
        long epoch = NOW.getEpochSecond() - 10;

        assertThat(verifier.verify(miniAppInitData(42L, epoch, "qid-A"), NOW)).isPresent();
        assertThat(verifier.verify(miniAppInitData(42L, epoch, "qid-B"), NOW))
                .as("query_id 不同 ⇒ 签名不同 ⇒ 非重放")
                .isPresent();
    }

    @Test
    void missingFieldsFail() {
        assertThat(verifier.verify(null, NOW)).isEmpty();
        assertThat(verifier.verify("", NOW)).isEmpty();
        assertThat(verifier.verify("   ", NOW)).isEmpty();
        assertThat(verifier.verify("id=42", NOW)).isEmpty();

        Map<String, String> noUser = fields(42L, NOW.getEpochSecond() - 10, "qid-1");
        noUser.remove("user");
        assertThat(verifier.verify(initData(noUser, miniAppKey()), NOW)).isEmpty();

        // 有 hash 但缺 auth_date
        Map<String, String> noAuth = fields(42L, NOW.getEpochSecond() - 10, "qid-1");
        noAuth.remove("auth_date");
        assertThat(verifier.verify(initData(noAuth, miniAppKey()), NOW)).isEmpty();
    }

    @Test
    void nonNumericAuthDateFails() {
        Map<String, String> f = fields(42L, NOW.getEpochSecond() - 10, "qid-1");
        f.put("auth_date", "soon");

        assertThat(verifier.verify(initData(f, miniAppKey()), NOW)).isEmpty();
    }

    @Test
    void unconfiguredTokenReturnsEmptyAndDoesNotThrow() {
        MiniAppInitDataVerifier unconfigured = new MiniAppInitDataVerifier(null, MAX_AGE);
        MiniAppInitDataVerifier blank = new MiniAppInitDataVerifier("   ", MAX_AGE);
        String initData = miniAppInitData(42L, NOW.getEpochSecond() - 10, "qid-1");

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThat(blank.isConfigured()).isFalse();
        assertThat(unconfigured.verify(initData, NOW)).isEmpty();
        assertThat(blank.verify(initData, NOW)).isEmpty();
    }
}
