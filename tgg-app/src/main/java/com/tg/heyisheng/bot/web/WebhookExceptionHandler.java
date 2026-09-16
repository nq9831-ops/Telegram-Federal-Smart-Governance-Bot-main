package com.tg.heyisheng.bot.web;

import com.tg.heyisheng.bot.common.util.MaskingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Webhook 全局异常处理。
 *
 * <p><b>核心策略</b>：Telegram 收到非 2xx 响应会重推同一条 update；若业务异常返回 5xx，
 * 会造成重试风暴。因此这里吞掉业务异常并统一返回 200。
 *
 * <p><b>代价（必须写清）</b>：异常不再触发 Telegram 重试，只能靠本地日志/告警发现。
 * 因此审计日志的落地在后续切片中不可省——否则异常会静默消失。
 *
 * <p>注意 secret 校验失败不经此处：那由 {@code SecretTokenFilter} 直接返回 401，属预期控制流。
 */
@RestControllerAdvice
public class WebhookExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Void> swallowAndLog(Exception ex) {
        // 只记录异常类型与已脱敏的消息，绝不记录请求体（可能含消息原文）
        log.warn("处理 update 时发生异常，已吞掉并返回 200 以避免 Telegram 重试风暴：{}: {}",
                ex.getClass().getSimpleName(),
                MaskingUtil.maskText(ex.getMessage()));
        return ResponseEntity.ok().build();
    }
}
