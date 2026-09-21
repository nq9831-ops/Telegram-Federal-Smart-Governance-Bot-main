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

    /**
     * 命令描述。
     *
     * <p>消费者是 **Telegram 客户端的命令菜单**——启动期由 {@link CommandMenuRegistrar}
     * 经 {@code setMyCommands} 注册（详见 {@code CommandMenuConfiguration}）。
     * ⚠️ 描述为空的命令**不会进菜单**（客户端会渲染成空白行），故新增命令务必填它。
     */
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

    /**
     * 是否需要「执行前先确认」（见 {@link Confirm}）。
     *
     * <p>默认 {@link Confirm#NEVER}——绝大多数命令不该被打断。
     * 该标注用于**不可逆或高影响**的操作：资金动作、终态裁决（复核/联邦申诉）、关闭本群、
     * 写合规证据等。判据不是「操作大不大」，而是「做错了能不能撤、会牵连谁」。
     *
     * <p><b>它只影响入口，不改变命令本身的语义</b>：确认通过后执行的仍是同一个 handler。
     * 因此对已有命令加标注是**行为增量**（多一步确认），不是行为改写。
     */
    Confirm confirm() default Confirm.NEVER;

    /**
     * 本命令在 {@code /menu} 面板里所属的业务域分类。
     *
     * <p>默认 {@link MenuCategory#OTHER}——未声明即落进「其他」分类，**仍会显示**（fail-visible：
     * 宁可多一个「其他」，也不让命令从面板里静默消失）。
     *
     * <p><b>为什么由命令自己声明</b>：与 {@link #requiredPermission()}、{@link #confirm()} 同款。
     * 若在菜单侧维护「命令 → 分类」的映射表，新增命令时必然有人忘了登记，表会悄悄过期
     * ——本项目在「哪些命令需要参数」的清单上已有同类教训。
     */
    MenuCategory category() default MenuCategory.OTHER;

    /**
     * 本命令是否**对全体成员开放的自助命令**——即进入 {@code /menu} 面板、对所有人可见。
     *
     * <p><b>默认 {@code false}，这是刻意的 fail-closed</b>：没声明就不进面板。
     * 反过来（默认公开）会有一个静默且危险的失效方式——有人新增一条「门控写在 handler 里、
     * 注解权限留 {@code NONE}」的平台命令（如商家资质复核），只要忘了登记可见性接缝，它就会被
     * 面板收录、对**所有人**可见，正是「向无权者暴露命令存在」那个原始缺陷的原样回归。
     * 现在的失效方式是「面板里看不到它」——会被发现（{@code CommandMenuContentTest} 断言
     * 该集合为空），而不是被忽略。
     *
     * <p>只对**无权限点**（{@code requiredPermission == NONE}）的命令有意义：
     * 带权限点的命令走 RBAC 判定，可见性由权限本身决定，与本属性无关。
     *
     * <p>与 {@link #clientMenu()} 的分工：本属性管「{@code /menu} 面板里有没有」，
     * 后者管「客户端 {@code /} 提示菜单里有没有」。两者互不影响。
     */
    boolean publicCommand() default false;

    /**
     * 本命令是否出现在 **Telegram 客户端 {@code /} 提示菜单**（输入 {@code /} 时弹出的那份）。
     *
     * <p><b>默认 {@code false}</b>：客户端菜单已收敛为**单一入口** {@code /menu}——其余命令一律
     * 经 {@code /menu} 面板按需呈现（从「背命令」到「点面板」）。故只有显式声明本属性的命令
     * 才进客户端菜单；新增命令默认不进，不会让那个入口重新长成一堵命令墙。
     *
     * <p>只对**无权限点**（{@code requiredPermission == NONE}）的命令有意义：带权限点的命令
     * 进客户端菜单会向无权者暴露其存在，{@code CommandMenuRegistrar.planMenus} 会剔除并告警。
     */
    boolean clientMenu() default false;
}
