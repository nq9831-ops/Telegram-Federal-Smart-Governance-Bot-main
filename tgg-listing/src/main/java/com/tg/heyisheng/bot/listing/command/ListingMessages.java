package com.tg.heyisheng.bot.listing.command;

import com.tg.heyisheng.bot.core.dispatch.DispatchMessages;

/**
 * 收录与商家（模块五/六）的**用户可见文案**——{@code *Messages} 家族在 listing 包的落点。
 *
 * <p><b>措辞冻结</b>：所有字符串**逐字节**取自搬迁前的原实现（含全角标点、换行与前导空格），
 * 搬迁不改一个字节。
 *
 * <p>组织规则见仓库根 {@code VOICE.md} 第八节：整句用常量；含插值的整行/整段用静态方法。
 *
 * <p><b>跨域共享</b>：{@code "申诉已提交（编号 #"} 与联邦申诉（模块八）逐字相同，
 * 故由 {@link DispatchMessages#APPEAL_SUBMITTED_PREFIX} 提供，两处共用一份。
 */
public final class ListingMessages {

    private ListingMessages() {
    }

    // ────────────── /merchant_apply ──────────────

    /** {@code /merchant_apply} 用法。 */
    public static final String MERCHANT_APPLY_USAGE = "用法：/merchant_apply 商家名称\n例：/merchant_apply 测试小铺";

    /** 取不到用户身份。 */
    public static final String MERCHANT_NO_IDENTITY = "无法识别你的用户身份，请稍后再试。";

    /** 名称超长。 */
    public static final String MERCHANT_NAME_TOO_LONG = "商家名称过长（上限 255 字符）。";

    /** 已有在办申请。 */
    public static String merchantOpenApplication(Object id) {
        return "你已有在办的入驻申请（编号 #" + id + "），请等待复核。";
    }

    /** 入驻申请已提交。 */
    public static String merchantSubmitted(Object id) {
        return "入驻申请已提交（编号 #" + id + "），等待资质复核。";
    }

    /** 找不到商家编号（复核 / 保证金 / 退出三处共用）。 */
    public static String merchantNotFound(Object id) {
        return "未找到商家编号 " + id + "。";
    }

    // ────────────── /merchant_review ──────────────

    /** {@code /merchant_review} 用法。 */
    public static final String MERCHANT_REVIEW_USAGE = "用法：/merchant_review 商家编号 结论\n"
            + "例：/merchant_review 1 approve\n"
            + "结论可为 approve（通过）/ reject（驳回）/ need-more（要求补充材料）。";

    /** 状态不允许复核。 */
    public static String merchantNotReviewable(Object status) {
        return "该申请当前状态为 " + status + "，不可复核。";
    }

    /** 复核结论已写入。 */
    public static String merchantReviewed(Object id, Object decision) {
        return "商家 #" + id + " 复核结论已写入：" + decision + "。";
    }

    // ────────────── /merchant_deposit ──────────────

    /** {@code /merchant_deposit} 用法。 */
    public static final String MERCHANT_DEPOSIT_USAGE = "用法：/merchant_deposit 商家编号 金额\n"
            + "例：/merchant_deposit 1 10000\n"
            + "（需先经 /merchant_review 复核通过）";

    /** 保证金已存在。 */
    public static String depositAlreadyExists(Object state) {
        return "该商家保证金已存在（当前 " + state + "），无需重复操作。";
    }

    /** 当前状态不可缴纳保证金。 */
    public static String depositNotPayable(Object status) {
        return "该商家当前状态为 " + status + "，不可缴纳保证金（需先复核通过）。";
    }

    /** 保证金已确认、入驻完成。 */
    public static String depositConfirmed(Object id, String amount) {
        return "保证金已确认（商家 #" + id + "，" + amount + " USDT），入驻完成。";
    }

    // ────────────── /merchant_settle ──────────────

    /** {@code /merchant_settle} 用法。 */
    public static final String MERCHANT_SETTLE_USAGE = "用法：/merchant_settle 商家编号 结论\n例：/merchant_settle 1 NONE（结论可为 NONE / UNRESOLVED / WITH_COMPENSATION）";

    /** 找不到保证金记录。 */
    public static String depositNotFound(Object id) {
        return "未找到商家编号 " + id + " 的保证金记录。";
    }

    /** 结算失败前缀（后接原因）。 */
    public static final String SETTLE_FAILED_PREFIX = "结算失败：";

    /** 全额退还。 */
    public static String settledRefunded(Object id, String amount, Object state) {
        return "已全额退还保证金（商家 #" + id + "，" + amount + " USDT），当前状态 " + state + "。";
    }

    /** 争议未结：保持冻结、无资金动作。 */
    public static String settledUnresolved(Object id, String amount) {
        return "争议未结：保证金保持冻结（商家 #" + id + "，" + amount
                + " USDT），本次未发生任何资金动作。争议结案后再执行一次本命令。";
    }

    /** 扣赔付后退还。 */
    public static String settledWithCompensation(String deduction, Object reason, Object id, Object state) {
        return "已按赔付扣除 " + deduction
                + " USDT（理由：" + reason + "），商家 #" + id
                + " 保证金当前状态 " + state + "。";
    }

    // ────────────── /merchant_exit ──────────────

    /** {@code /merchant_exit} 用法。 */
    public static final String MERCHANT_EXIT_USAGE = "用法：/merchant_exit 商家编号\n例：/merchant_exit 1";

    /** 仅商家本人。 */
    public static final String MERCHANT_NOT_OWNER = "只有商家本人可以申请退出。";

    /** 无保证金可冻结。 */
    public static final String EXIT_NO_DEPOSIT = "该商家尚未缴纳保证金，无可冻结的资金。";

    /** 退出申请已受理。 */
    public static String exitAccepted(Object id) {
        return "退出申请已受理（商家 #" + id + "），保证金已冻结，等待争议核查后结算。";
    }

    // ────────────── /listing_add ──────────────

    /** {@code /listing_add} 用法。 */
    public static final String LISTING_ADD_USAGE = "用法：/listing_add 邀请链接\n例：/listing_add https://t.me/+AbCdEfGh";

    /** 非群会话。 */
    public static final String LISTING_NOT_A_GROUP = "请在要收录的群内执行本命令。";

    /** 邀请链接无效。 */
    public static final String LISTING_INVALID_LINK = "邀请链接无效：应以 https://t.me/ 开头。";

    /** 已提交收录。 */
    public static final String LISTING_ADDED = "已提交收录，将定期验证链接有效性。";

    /** 已存在（重复提交已忽略）。 */
    public static final String LISTING_ALREADY_EXISTS = "该群已在收录库中（重复提交已忽略）。";

    // ────────────── /listing_appeal ──────────────

    /** {@code /listing_appeal} 用法。 */
    public static final String LISTING_APPEAL_USAGE = "用法：/listing_appeal 收录编号 理由\n例：/listing_appeal 1 群只是改成邀请制了";

    /** 条目未下架。 */
    public static final String LISTING_NOT_SUSPENDED = "该群当前未被下架，无需申诉。";

    /** 仅提交者本人。 */
    public static final String LISTING_NOT_SUBMITTER = "只有该群的提交者本人可以申诉。";

    /** 找不到收录编号。 */
    public static String listingNotFound(Object id) {
        return "未找到收录编号 " + id + "。";
    }

    /** 异议期已过。 */
    public static String disputeWindowClosed(Object days) {
        return "异议期（" + days + " 天）已过，无法申诉。";
    }

    /** 收录申诉已提交（前缀与联邦申诉共享）。 */
    public static String listingAppealSubmitted(Object id) {
        return DispatchMessages.APPEAL_SUBMITTED_PREFIX + id + "），将由管理员审核。";
    }

    // ────────────── /listing_list ──────────────

    /** 收录库为空。 */
    public static final String LISTING_LIST_EMPTY = "收录库当前没有有效条目。";

    /** 列表标题。 */
    public static final String LISTING_LIST_PREFIX = "收录库（有效）：";

    /** 无标题条目的占位。 */
    public static final String LISTING_LIST_UNTITLED = "（未命名）";

    /** 列表的一行。 */
    public static String listingListLine(Object id, String title) {
        return "\n#" + id + " " + title;
    }

    /** 列表截断提示（句式与教学规则列表共享）。 */
    public static String listingListTruncated(int total, int shown) {
        return DispatchMessages.truncatedNotice(total, shown);
    }

    // ────────────── /merchant_status ──────────────

    /** 尚未提交过申请。 */
    public static final String MERCHANT_STATUS_NONE =
            "你还没有提交过商家入驻申请。\n用法：/merchant_apply 商家名称（例：/merchant_apply 测试小铺）";

    /** 状态：已提交。 */
    public static final String STATUS_SUBMITTED = "已提交，等待资质复核";

    /** 状态：复核中。 */
    public static final String STATUS_UNDER_REVIEW = "资质复核中";

    /** 状态：通过待缴保证金。 */
    public static final String STATUS_APPROVED = "资质已通过，等待缴纳保证金";

    /** 状态：需补充材料。 */
    public static final String STATUS_NEED_MORE = "需补充材料，请修改后重新提交";

    /** 状态：未通过。 */
    public static final String STATUS_REJECTED = "资质未通过";

    /** 状态：待缴保证金。 */
    public static final String STATUS_DEPOSIT_PENDING = "待缴纳保证金";

    /** 状态：营业中。 */
    public static final String STATUS_ACTIVE = "入驻成功（营业中）";

    /** 等级行（仅在商家有等级时拼在末尾）。 */
    public static String merchantTierSuffix(Object tier) {
        return "\n等级：" + tier;
    }

    /** 商家入驻状态整段。 */
    public static String merchantStatus(Object id, String name, String statusText, String tierSuffix) {
        return "商家入驻状态：\n编号 #" + id + " · " + name
                + "\n状态：" + statusText + tierSuffix;
    }

    // ────────────── 下架通知（私聊提交者）──────────────

    /** 收录条目下架通知（含申诉指引——没有编号的告知等于把用户引到一个用不了的入口）。 */
    public static String delistedNotice(Object id) {
        return "你提交收录的群（编号 #" + id + "）经定期验证已失效，已从收录库下架。\n"
                + "若你认为这是误判（例如群只是改成了邀请制），可在 7 天异议期内发送：\n"
                + "/listing_appeal " + id + " 你的申诉理由";
    }
}
