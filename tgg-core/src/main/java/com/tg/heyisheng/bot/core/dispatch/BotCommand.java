package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.core.permission.Permission;
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

    /**
     * 执行本命令所需的权限。
     *
     * <p>默认 {@link Permission#NONE} —— 即未显式声明权限的命令对所有人开放，
     * 保持既有命令（如 {@code /echo}）行为不变。
     */
    Permission requiredPermission() default Permission.NONE;

    /**
     * 本命令是否在该群「功能已关闭」时仍可执行。
     *
     * <p>默认 {@code false}——绝大多数命令在停用群内不应生效。
     *
     * <p><b>必须为 true 的例外</b>：用于<b>恢复</b>群组状态的命令（如 {@code /enable}）。
     * 否则一旦某群被停用，群内任何命令都进不来，该群将<b>永久锁死</b>，
     * 只能由运维直接改数据库恢复——这是实测暴露的真实缺陷，不是理论风险。
     */
    boolean worksWhenDisabled() default false;
}
