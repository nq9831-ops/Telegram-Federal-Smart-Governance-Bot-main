package com.tg.heyisheng.bot.core.notify;

/**
 * 通知级别（模块十 §11.1）——三级分类决定频率额度。
 *
 * <p><b>规格来自 V5.0 原文</b>：
 * <ul>
 *   <li>{@link #URGENT} 紧急（封禁 / 硬红线处罚 / 解封 / 信用分恢复）——<b>不受频率限制</b>；</li>
 *   <li>{@link #IMPORTANT} 重要（信用分变动 / 申诉进度 / 群组状态变更）——每小时最多 3 条；</li>
 *   <li>{@link #NORMAL} 普通（推荐更新 / 系统公告 / 教学贡献）——每小时最多 1 条、每天最多 5 条。</li>
 * </ul>
 *
 * <p><b>为什么紧急不限额</b>：封禁与解封是用户必须立刻知道的权益变动，
 * 用频率门把它们压住会让「冤封」在无人知晓的情况下持续生效。
 */
public enum NotificationLevel {

    /** 紧急：不受限。 */
    URGENT(0, 0),

    /** 重要：每小时 3 条。 */
    IMPORTANT(3, 0),

    /** 普通：每小时 1 条、每天 5 条。 */
    NORMAL(1, 5);

    private final int perHour;
    private final int perDay;

    NotificationLevel(int perHour, int perDay) {
        this.perHour = perHour;
        this.perDay = perDay;
    }

    /** 每小时上限；{@code 0} 表示不限。 */
    public int perHour() {
        return perHour;
    }

    /** 每天上限；{@code 0} 表示不限。 */
    public int perDay() {
        return perDay;
    }

    /** 是否不受任何频率限制。 */
    public boolean unlimited() {
        return perHour <= 0 && perDay <= 0;
    }
}
