package com.tg.heyisheng.bot.admin.approval;

/**
 * 审批中心（模块十一 §12.1）的**用户可见文案**——{@code *Messages} 家族在 admin 包的落点。
 *
 * <p><b>措辞冻结</b>：所有字符串**逐字节**取自搬迁前的原实现，搬迁不改一个字节。
 *
 * <p>组织规则见仓库根 {@code VOICE.md} 第八节。
 */
public final class AdminMessages {

    private AdminMessages() {
    }

    /** 超时催办标题：升级档。 */
    public static final String OVERDUE_TITLE_ESCALATED = "⚠️ 审批升级";

    /** 超时催办标题：提醒档。 */
    public static final String OVERDUE_TITLE_REMIND = "审批提醒";

    /** 审批超时催办（发给平台白名单成员，紧急级）。 */
    public static String overdueNotice(Object id, Object riskLevel, long ageHours, boolean escalated) {
        return (escalated ? OVERDUE_TITLE_ESCALATED : OVERDUE_TITLE_REMIND)
                + "：待办 #" + id
                + "（等级 " + riskLevel + "，已积压 " + ageHours + " 小时）"
                + " 仍未处理，请登录治理后台处理。";
    }
}
