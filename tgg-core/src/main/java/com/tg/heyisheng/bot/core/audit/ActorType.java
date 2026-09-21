package com.tg.heyisheng.bot.core.audit;

/**
 * 审计主体类型——让 {@code actor_id} 从「一个裸 userId」变成「(类型, id) 二元组」。
 *
 * <p><b>为什么必须有类型</b>：超管/操作员的账号 id 与 Telegram userId 落在<b>同一个 BIGINT
 * 数值空间</b>里。若审计只按 {@code actor_id} 单条件查询，则 {@code /export_my_data} 这类
 * 「导出关于我自己的数据」的路径会把 id=42 的<span>后台账号</span>记录发给 userId=42 的
 * <span>TG 用户</span>——两者本该互不可见。加类型即把这条越权从「靠数值不撞车」变成
 * 「结构上不可能」。
 *
 * <p><b>两类主体</b>：
 * <ul>
 *   <li>{@link #TG_USER} —— Telegram 用户。命令路径的发起者、审核路径的被处置者。</li>
 *   <li>{@link #ADMIN_ACCOUNT} —— 后台账号（超管 / 操作员）。经 Web 会话操作。</li>
 * </ul>
 *
 * <p>纯系统级动作（无关联主体）以 {@code actor_id = null} 表示，类型取 {@link #TG_USER}
 * （无主体时类型无意义，与历史行默认一致）。
 */
public enum ActorType {

    /** Telegram 用户（命令路径 / 审核路径的被处置者）。 */
    TG_USER,

    /** 后台账号（超管 / 操作员）。 */
    ADMIN_ACCOUNT
}
