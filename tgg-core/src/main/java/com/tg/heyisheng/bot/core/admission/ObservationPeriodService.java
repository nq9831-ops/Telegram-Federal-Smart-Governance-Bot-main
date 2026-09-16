package com.tg.heyisheng.bot.core.admission;

import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.objects.ChatPermissions;

import java.time.Duration;
import java.time.Instant;

/**
 * 观察期：给刚通过验证的新成员施加**限时**权限限制。
 *
 * <p><b>限制强度取"温和"</b>：允许发文字，但禁媒体/贴纸/投票/网页预览/邀请/改群信息。
 * 理由：广告号的典型滥用是发图、发链接、拉人——挡住这些即可，同时不阻断正常寒暄，
 * 避免新用户"进群就被禁言"的恶劣体验。
 *
 * <p><b>{@code untilDate} 必须设置</b>：{@code RestrictChatMember} 不带它即为**永久限制**，
 * 新成员将永远无法正常发言。设了它则由 Telegram **到期自动解禁**——这也是本切片
 * 不需要自建定时任务的原因（与"超时移出"不同，那里是主动踢人）。
 *
 * <p>与 {@code VerificationTimeoutSweeper} 的区别需分清：
 * 那个是 <b>ban</b>（移出群），这个是 <b>restrict</b>（留在群里但受限）。
 */
public class ObservationPeriodService {

    private static final Logger log = LoggerFactory.getLogger(ObservationPeriodService.class);

    private final ModerationActionSender sender;
    private final Duration period;

    public ObservationPeriodService(ModerationActionSender sender, Duration period) {
        this.sender = sender;
        this.period = period;
    }

    /**
     * 施加观察期限制。
     *
     * <p>周期为 0 或负数表示**不启用**观察期，直接跳过。
     * 参数不合法时静默返回（入群链路不应被它打断）。
     */
    public void apply(Long chatId, Long userId) {
        if (chatId == null || userId == null) {
            return;
        }
        if (period == null || period.isZero() || period.isNegative()) {
            log.debug("观察期未启用（周期={}），跳过限制", period);
            return;
        }

        int until = (int) (Instant.now().getEpochSecond() + period.toSeconds());
        sender.send(RestrictChatMember.builder()
                .chatId(chatId)
                .userId(userId)
                .permissions(mildPermissions())
                .untilDate(until)
                .build());
        log.info("已施加观察期限制（chatId={}, 时长={}秒）", chatId, period.toSeconds());
    }

    /** 温和限制：只留"发文字"，其余显式关掉（便于阅读与审计）。 */
    private static ChatPermissions mildPermissions() {
        return ChatPermissions.builder()
                .canSendMessages(true)
                .canSendAudios(false)
                .canSendDocuments(false)
                .canSendPhotos(false)
                .canSendVideos(false)
                .canSendVideoNotes(false)
                .canSendVoiceNotes(false)
                .canSendPolls(false)
                .canSendOtherMessages(false)
                .canAddWebPagePreviews(false)
                .canChangeInfo(false)
                .canInviteUsers(false)
                .canPinMessages(false)
                .canManageTopics(false)
                .build();
    }
}
