package com.tg.heyisheng.bot.core.interaction;

import java.util.Set;

/**
 * 命令展示<b>策展</b>——回答「这条命令对这个查看者是否要从面板上收走」
 * （可见性的反向补充：{@link MenuVisibility} 说「谁能看见」，本接口说「谁不需要看见」）。
 *
 * <p><b>存在理由</b>（用户 2026-09-22 拍板）：「商家收录」面把「提交商家入驻」的自助链与
 * 「管理商家」的资质/保证金混排，对管事的人是模糊的——他们管商家，不申请入驻。
 * 策展按查看者身份收走自助链，让管理者的面里只有管理。
 *
 * <p><b>只收展示，不动执行</b>：被收走的命令仍可直接键入执行（用户原话是「不需要」，
 * 不是「不存在」）——本判定不进入执行门控，与 {@code CommandDispatcher} 零耦合。
 *
 * <p><b>为什么在 core 定义、由各模块注册</b>：同 {@link MenuVisibility} 的依赖倒置——
 * 「谁是管理者」由各模块自己的 Guard/权限判定回答，core 只定义接缝 + 聚合，
 * 未注册即零影响。
 *
 * <p><b>实现方纪律</b>：判定必须与该功能的管理判定<b>同源</b>（调同一个 Guard 的同一个方法），
 * 否则会出现「已经不管事了却还被收走自助链」这类漂移。<b>fail-open</b>：身份不明就不收——
 * 收起是策展、不是安全门，宁可多显示也不误藏；安全门的 fail-closed 语义属于
 * {@link MenuVisibility}，两条接缝各守各的承诺。
 */
public interface MenuCurator {

    /**
     * 本策展负责的命令（**主命令名**，小写、不含斜杠与别名）。
     *
     * <p>每条命令至多归属一个策展——重复登记在装配期即失败（同 {@code MenuVisibility} 口径）。
     */
    Set<String> commands();

    /**
     * 对该查看者是否收起这些命令。
     *
     * @param chatId 目标会话（负数为群）；有群内语义的判定用它
     * @param userId 发起者；{@code null} 表示身份不可识别——实现应返回 {@code false}（不收，fail-open）
     */
    boolean hides(long chatId, Long userId);
}
