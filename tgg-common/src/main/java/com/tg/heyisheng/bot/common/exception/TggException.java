package com.tg.heyisheng.bot.common.exception;

/**
 * 项目异常基类。
 *
 * <p>所有自定义异常继承此类，便于在统一出口处识别「本项目已知异常」并做脱敏处理。
 */
public class TggException extends RuntimeException {

    public TggException(String message) {
        super(message);
    }

    public TggException(String message, Throwable cause) {
        super(message, cause);
    }
}
