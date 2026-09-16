package com.tg.heyisheng.bot.core.dispatch;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个命令处理器，并声明其命令名与别名。
 *
 * <p>被标注的类会由 {@link CommandRegistry} 在启动时扫描并注册。
 *
 * <p><b>注意命名</b>：本注解位于 {@code com.tg.heyisheng.bot.core.dispatch}，
 * 与 TelegramBots 自带的 {@code org.telegram.telegrambots.meta.api.objects.commands.BotCommand}
 * （那是 Bot API 的「命令描述」数据对象）<b>同名但不同物</b>，同时使用时需留意 import。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface BotCommand {

    /** 主命令名，不含前导斜杠，例如 {@code "echo"}。 */
    String value();

    /** 命令描述（供 /help 等展示，切片 1 暂未使用）。 */
    String description() default "";

    /** 别名，例如 {@code {"ping"}}。 */
    String[] aliases() default {};
}
