package com.tg.heyisheng.bot.listing.verification;

/**
 * 群组链接验证结果（三态，必须分开）。
 *
 * <p><b>为什么必须三态</b>：把「网络抖动 / 无 bot token / API 限流」这类<b>探测本身失败</b>
 * 误判成「群已失效」，会让收录库把好群批量下架——这是本模块最危险的失败模式
 * （设计文档 §3.2 与 §9 反证均把这条列为首要项）。因此：
 * <ul>
 *   <li>{@link #FAIL} 表示 <b>Telegram 明确告诉我们这个群/链接失效了</b>，才会累加 {@code fail_count}；</li>
 *   <li>{@link #ERROR} 表示 <b>我们没能得到可信结论</b>，只落一条验证记录，<b>绝不动 {@code fail_count}</b>。</li>
 * </ul>
 */
public enum VerificationResult {

    /** 链接可达：群存在且机器人可访问。 */
    OK,

    /** 链接确实失效（Telegram 明确回「群不存在 / 机器人被移出 / 邀请链接已撤销」）。 */
    FAIL,

    /** 探测本身失败，结论未知——<b>不计入 {@code fail_count}</b>。 */
    ERROR
}
