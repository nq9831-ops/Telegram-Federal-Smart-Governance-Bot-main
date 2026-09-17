package com.tg.heyisheng.bot.common.exception;

/**
 * 配置缺失或非法。
 *
 * <p>用于启动期 fail-fast：宁可启动失败并给出明确原因，也不要带着不安全的默认值继续运行
 * （例如 Webhook secret 未配置却仍接受请求）。
 */
public class TggConfigException extends TggException {

    public TggConfigException(String message) {
        super(message);
    }

    /** 带根因的构造器——把底层异常（如密钥解析失败）的 cause 保留下来，便于诊断。 */
    public TggConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
