package com.tg.heyisheng.bot.escrow;

import com.tg.heyisheng.bot.core.notify.Notification;
import com.tg.heyisheng.bot.core.notify.NotificationCategory;
import com.tg.heyisheng.bot.core.notify.NotificationDispatcher;
import com.tg.heyisheng.bot.core.notify.NotificationLevel;

/**
 * 担保交易的进度通知：<b>双通道 + 各自开关</b>。
 *
 * <p><b>通道分工</b>（见 {@link EscrowProperties.Notify}）：群内让进度公开可见，
 * 私聊确保对方收到（对方可能不在那个群里）。两个开关都关 = 交易对他方静默，
 * 只剩命令回执给操作者——运维需自知这一点。
 *
 * <p><b>额度分池</b>：走 {@link NotificationCategory#ESCROW} 池，交易消息不会挤占
 * 封禁告知、信用分变动等治理通知的额度（分池的由来见该枚举）。
 *
 * <p><b>私聊通道可为空</b>（通知模块未装配时）：此时私聊静默跳过，群内通道照常——
 * 与 {@code ObjectProvider} 可选取用的既有范式一致，不让通知缺席拖垮交易主链路。
 */
public class EscrowNotifier {

    private final NotificationDispatcher privateChannel;
    private final EscrowGroupSender groupChannel;
    private final EscrowProperties properties;

    public EscrowNotifier(NotificationDispatcher privateChannel, EscrowGroupSender groupChannel,
                          EscrowProperties properties) {
        this.privateChannel = privateChannel;
        this.groupChannel = groupChannel == null ? EscrowGroupSender.logging() : groupChannel;
        this.properties = properties;
    }

    /**
     * 把一条进度通知发给指定当事方（按开关走双通道）。
     *
     * @param recipientUserId 收件人（交易对方）
     * @param groupChatId     交易所在群；{@code null} = 无群上下文（如私聊里发起）
     * @param text            正文（由调用方从 {@link EscrowMessages} 取）
     */
    public void notifyParty(long recipientUserId, Long groupChatId, String text) {
        EscrowProperties.Notify switches = properties.getNotify();
        if (switches.isPrivateEnabled() && privateChannel != null) {
            privateChannel.notify(NotificationCategory.ESCROW,
                    new Notification(NotificationLevel.IMPORTANT, recipientUserId, text));
        }
        if (switches.isGroupEnabled() && groupChatId != null) {
            groupChannel.send(groupChatId, text);
        }
    }
}
