package com.tg.heyisheng.bot.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.admin.identity.AdminAccount;
import com.tg.heyisheng.bot.admin.identity.AdminAccountRepository;
import com.tg.heyisheng.bot.admin.identity.AdminRole;
import com.tg.heyisheng.bot.admin.identity.PasswordHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TOTP **自助绑定**（模块十一 · 账号体系）。
 *
 * <p><b>要解决的缺口</b>：原实现只有超管端点 {@code POST /admin/accounts/{id}/totp}，它把**含密钥**的
 * {@code otpauth://} URL 回给<b>超管</b>。于是超管可以替任意操作员启用第二因子并拿到密钥 ⇒ 能自己算出
 * 有效验证码 ⇒ 第二因子对超管形同虚设（而 TOTP 的意义之一，正是防住「超管重置密码后直接登录」）。
 *
 * <p><b>修法的取向</b>：密钥<b>只应被账号本人看到一次</b>。故新增本人端点
 * {@code POST /admin/auth/totp}（启用并回 URL 给本人）与 {@code DELETE /admin/auth/totp}（解绑，
 * 需当前有效验证码），并<b>移除</b>超管那条会泄露密钥的启用入口（解绑保留为恢复通道，且照旧审计）。
 *
 * <p><b>本测试自带 TOTP 计算</b>（HMAC-SHA1 + base32 + 动态截断），刻意<b>不复用</b>
 * {@code TotpGenerator} —— 既避免依赖其包私有 API，也让断言成为对生产实现的**独立交叉验证**：
 * 若生产端算法有偏差，这里算出的码同样校验不过。
 */
@SpringBootTest(properties = {
        "tgg.admin.api-token=test-admin-token",
        // 本仓踩过的坑：同一 Spring 上下文内多个 admin IT 的登录会累积打满 per-IP 限流（默认 20/min），
        // 结果是 429 打红与限流无关的用例。这里抬到 1000。
        "tgg.admin.login-max-per-minute=1000"
})
@AutoConfigureMockMvc
class AdminTotpSelfServiceApiIT {

    private static final String PASSWORD = "pw-self-123";
    /**
     * 每个用例一个唯一用户名。刻意声明为**实例字段**：JUnit 5 为每个测试方法新建测试类实例，
     * 故其初始化器逐用例求值；写成 {@code static} 会一个 JVM 只算一次，第二个用例即撞唯一键
     * （本测试首版就踩了这个坑，3/4 用例在 @BeforeEach 直接 ERROR）。
     */
    private final String user = "totp-self-" + System.nanoTime();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AdminAccountRepository accounts;

    private long accountId;

    @BeforeEach
    void createOperatorAccount() {
        AdminAccount saved = accounts.save(new AdminAccount(
                user, PasswordHasher.hash(PASSWORD), AdminRole.OPERATOR, Instant.now()));
        accountId = saved.getId();
    }

    // ───────────────────────── 自助启用 ─────────────────────────

    @Test
    void selfServiceEnableReturnsSecretToOwnerAndThenTotpIsRequired() throws Exception {
        String token = login(null);

        // 启用：密钥回给**本人**（响应里含 secret），响应只此一次。
        String enableBody = mockMvc.perform(post("/admin/auth/totp")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otpauthUrl").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String otpauthUrl = objectMapper.readTree(enableBody).get("otpauthUrl").asText();

        assertThat(otpauthUrl)
                .as("otpauth URL 应指向本账号，且带上密钥供 authenticator 扫码")
                .startsWith("otpauth://totp/")
                .contains(user)
                .contains("secret=");

        // 启用后：不带验证码登录必须失败（第二因子真生效）。
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());

        // 带**独立算出**的验证码登录成功。
        String code = totpCode(secretOf(otpauthUrl), Instant.now());
        login(code);
    }

    // ───────────────────────── 自助解绑 ─────────────────────────

    @Test
    void selfServiceDisableRequiresAValidCode() throws Exception {
        String token = login(null);
        String otpauthUrl = enable(token);
        String code = totpCode(secretOf(otpauthUrl), Instant.now());

        // 不带码 → 400（否则「拿到会话就能关掉第二因子」，等于第二因子白设）
        mockMvc.perform(delete("/admin/auth/totp").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        // 错码 → 400
        mockMvc.perform(delete("/admin/auth/totp?code=000000")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        // 正确码 → 200，且此后不带码也能登录（第二因子确已移除）
        mockMvc.perform(delete("/admin/auth/totp?code=" + code)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        login(null);
    }

    // ─────────────────── 超管不得再拿到他人密钥 ───────────────────

    @Test
    void superAdminCanNoLongerEnableTotpForOthers() throws Exception {
        String token = login(null);

        // 该入口已移除：路径上只剩 DELETE，故 POST 落到「方法不支持」（405），不再是 200 + 密钥。
        mockMvc.perform(post("/admin/accounts/" + accountId + "/totp")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void selfServiceEndpointsRejectAnonymousCallers() throws Exception {
        mockMvc.perform(post("/admin/auth/totp")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/admin/auth/totp").param("code", "123456"))
                .andExpect(status().isUnauthorized());
    }

    // ───────────────────────── 夹具 ─────────────────────────

    /** 登录并取会话令牌；{@code totpCode} 为 null 时不带验证码。 */
    private String login(String totpCode) throws Exception {
        String body = "{\"username\":\"" + user + "\",\"password\":\"" + PASSWORD + "\""
                + (totpCode == null ? "" : ",\"totpCode\":\"" + totpCode + "\"") + "}";
        String json = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("token").asText();
    }

    /** 用既有会话启用 TOTP，返回 otpauth URL。 */
    private String enable(String token) throws Exception {
        String json = mockMvc.perform(post("/admin/auth/totp")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("otpauthUrl").asText();
    }

    private static String secretOf(String otpauthUrl) {
        int i = otpauthUrl.indexOf("secret=");
        assertThat(i).as("otpauth URL 必须带 secret").isGreaterThanOrEqualTo(0);
        String rest = otpauthUrl.substring(i + "secret=".length());
        int amp = rest.indexOf('&');
        return amp < 0 ? rest : rest.substring(0, amp);
    }

    // ─────────── 自带 TOTP（RFC 6238）——刻意不复用生产实现 ───────────

    private static String totpCode(String base32Secret, Instant now) throws Exception {
        long counter = now.getEpochSecond() / 30;
        byte[] key = base32Decode(base32Secret);
        byte[] data = ByteBuffer.allocate(8).putLong(counter).array();

        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(data);

        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        return String.format("%06d", binary % 1_000_000);
    }

    private static byte[] base32Decode(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String clean = s.replace("=", "").toUpperCase(java.util.Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char c : clean.toCharArray()) {
            int value = alphabet.indexOf(c);
            if (value < 0) {
                continue;
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}
