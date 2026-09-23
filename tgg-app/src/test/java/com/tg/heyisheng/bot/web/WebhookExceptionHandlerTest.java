package com.tg.heyisheng.bot.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook 异常处理的<b>分类</b>守卫：什么该吞成 200、什么该如实返回状态码。
 *
 * <p><b>它守的是什么</b>：{@code swallowAndLog}（{@code @ExceptionHandler(Exception.class)}）
 * 为防 Telegram 重试风暴把所有异常吞成 200——但它<b>过宽</b>时会把"扫描器噪声"与"真实故障"
 * 一起伪装成 200，让真问题淹没在成堆的 ERROR 里（本项目已因此修过两次：
 * 09-19 的未知路径 → 404、09-20 的 GET 单段路径 → 405）。
 *
 * <p><b>本条是同一族的第三个形态</b>（09-23 生产日志实测）：外部对 webhook 路径 POST
 * {@code Content-Type: text/plain} 抛 {@code HttpMediaTypeNotSupportedException}，
 * 落进通用处理器被吞成 200 + 整段 ERROR 堆栈，累计 <b>8485 条</b>。
 * 它与 Telegram 的 update 投递无关（update 恒为 {@code application/json}），
 * 故如实返回 415 不削弱重试风暴策略。
 */
class WebhookExceptionHandlerTest {

    private final WebhookExceptionHandler handler = new WebhookExceptionHandler();

    @Test
    void unsupportedMediaTypeReturns415InsteadOf200() {
        ResponseEntity<Void> response = handler.unsupportedMediaType(
                new HttpMediaTypeNotSupportedException("Content-Type 'text/plain' is not supported"));

        assertThat(response.getStatusCode())
                .as("请求体类型不支持须返回 415——吞成 200 会让噪声伪装成故障")
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void genericHandlerStillSwallowsBusinessExceptionsAs200() {
        ResponseEntity<Void> response = handler.swallowAndLog(new IllegalStateException("业务故障"));

        assertThat(response.getStatusCode())
                .as("重试风暴策略不得被削弱：真实业务异常仍吞成 200（Telegram 不重推）")
                .isEqualTo(HttpStatus.OK);
    }
}
