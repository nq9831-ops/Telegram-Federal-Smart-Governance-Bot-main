package com.tg.heyisheng.bot;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端验证：真实 Spring 上下文 + 库的 {@code @RestController} + 我们的 Filter 与分发链。
 *
 * <p>三条验收线全部覆盖：合法 secret → 200；secret 缺失/错误 → 401；
 * 处理器抛异常 → <b>仍是 200</b>（避免 Telegram 重试风暴）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebhookDispatchIT {

    private static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    /**
     * 校验值<b>从配置读取</b>（测试环境由 {@code TGG_TEST_WEBHOOK_SECRET} 注入）。
     *
     * <p>刻意不硬编码：硬编码会让测试期望与配置各自漂移（改了一边另一边静默失败），
     * 且仓库里会留下字面凭据。
     */
    @Value("${tgg.webhook.secret}")
    private String secret;
    /** 本测试专用的群 ID——刻意不同于人工验收常用的 -100，避免与手工数据相撞。 */
    private static final long TEST_CHAT_ID = -777001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GroupConfigService groupConfigService;

    /**
     * 建立前置条件：确保本测试用群处于「启用」状态。
     *
     * <p>必须显式设置——测试直连真实 MySQL，库里可能存在他处写入的
     * {@code enabled = false} 记录（例如人工验收留下的数据），
     * 那会让中间件如实中断链路、测试失败在一个与本次改动无关的原因上。
     * 依赖库的既有状态是测试隔离缺陷，不能靠"记得清库"来规避。
     */
    @BeforeEach
    void ensureTestChatEnabled() {
        groupConfigService.setEnabled(TEST_CHAT_ID, true);
    }

    @TestConfiguration
    static class FailingHandlerConfig {
        @Bean
        BoomHandler boomHandler() {
            return new BoomHandler();
        }
    }

    @BotCommand("boom")
    static class BoomHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            throw new IllegalStateException("模拟业务异常：".repeat(20));
        }
    }

    @Test
    void validSecretInvokesHandlerAndReturnsItsReply() throws Exception {
        mockMvc.perform(post("/webhook")
                        .header(SECRET_HEADER, secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("/echo")))
                .andExpect(status().isOk())
                // 关键断言语义：只查状态码会掩盖「链路断了但库仍回 200」。
                // 必须验证 handler 的回复真的出现在响应体里。
                .andExpect(content().string(containsString("pong")));
    }

    @Test
    void missingSecretReturns401() throws Exception {
        mockMvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("/echo")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongSecretReturns401() throws Exception {
        mockMvc.perform(post("/webhook")
                        .header(SECRET_HEADER, secret + "-wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("/echo")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void handlerExceptionStillReturns200() throws Exception {
        mockMvc.perform(post("/webhook")
                        .header(SECRET_HEADER, secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("/boom")))
                .andExpect(status().isOk());
    }

    private static String updateJson(String command) {
        // 必须带 from：链首 AuthenticationMiddleware 要求可识别的发送者，
        // 缺 from 会让链路在中途中断（这正是本测试此前「假通过」的原因）。
        return """
                {
                  "update_id": 1,
                  "message": {
                    "message_id": 10,
                    "date": 1700000000,
                    "from": {"id": 42, "is_bot": false, "first_name": "Test"},
                    "text": "%s",
                    "chat": {"id": %d, "type": "supergroup"},
                    "entities": [{"type": "bot_command", "offset": 0, "length": %d}]
                  }
                }
                """.formatted(command, TEST_CHAT_ID, command.length());
    }
}
