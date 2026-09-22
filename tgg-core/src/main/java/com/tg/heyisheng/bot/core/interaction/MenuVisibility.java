package com.tg.heyisheng.bot.core.interaction;

import java.util.Set;

/**
 * 命令可见性接缝——回答「这条命令对该用户是否可见」（模块九 §7.4 的 v2 项）。
 *
 * <p><b>存在理由</b>：{@code /menu} 的过滤依赖 {@code @BotCommand.requiredPermission}，
 * 但有一批命令的权限载体是**平台层全局白名单**（{@code ModerationReviewGuard} /
 * {@code FederationAdminGuard}——原 {@code MerchantReviewGuard} 已随商家授权改判裁撤），
 * 注解权限是 {@code NONE}——
 * **注册表判不出「此人是否可见」**，于是这些命令一向进不了 /menu。
 *
 * <p><b>为什么在 core 定义、由各模块注册</b>：判定白名单需要各模块自己的 {@code Guard}，
 * 而 {@code tgg-core} 处于依赖链底层、**物理上看不到** {@code tgg-listing} / {@code tgg-federation}。
 * 故照 {@code TeachGate} 的依赖倒置：core 只定义接缝 + 聚合，上层模块注册自己的实现即自动并入
 * ——<b>未接线即零影响</b>。
 *
 * <p><b>实现方纪律（安全）</b>：
 * <ul>
 *   <li>本判定直接决定「命令是否出现在面板上」。无权者的面板里**不得**出现该命令——
 *       与命令层「权限不足即静默」同口径（回复「权限不足」等于确认了命令存在）。</li>
 *   <li>必须与命令执行时的判定**同源**（调同一个 Guard 的同一个方法），否则菜单会显示
 *       「点了却不生效」的按钮，或（更糟）对无权者暴露命令。</li>
 * </ul>
 */
public interface MenuVisibility {

    /**
     * 本接缝负责的命令（**主命令名**，小写、不含斜杠与别名）。
     *
     * <p>每条命令至多归属一个接缝——重复登记会在装配期失败，不留到运行期（同
     * {@code CommandRegistry} 的命令名冲突口径）。
     */
    Set<String> commands();

    /**
     * 这些命令对该用户是否可见。
     *
     * @param chatId 目标群（负数）；白名单类判定通常忽略它，但保留以便将来有群内语义的接缝
     * @param userId 发起者；{@code null} 表示身份不可识别，应判为**不可见**
     */
    boolean visible(long chatId, Long userId);
}
