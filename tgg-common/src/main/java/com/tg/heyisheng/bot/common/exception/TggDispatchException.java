package com.tg.heyisheng.bot.common.exception;

/**
 * 更新（Update）分发过程中出现的异常——中间件链或命令分发阶段。
 */
public class TggDispatchException extends TggException {

    public TggDispatchException(String message) {
        super(message);
    }

    public TggDispatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
