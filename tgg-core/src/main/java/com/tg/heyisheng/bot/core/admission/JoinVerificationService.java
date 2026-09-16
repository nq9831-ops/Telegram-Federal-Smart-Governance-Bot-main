package com.tg.heyisheng.bot.core.admission;

import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.Duration;
import java.util.List;

/**
 * 入群验证：新人入群 → 登记 + 发带按钮的验证消息。
 *
 * <p><b>为什么经主动通道发送而不是作为返回值</b>：webhook 模式下 handler 返回值只能执行**一个**
 * Bot API 方法，而一次入群事件可能带**多个**新成员（批量拉人是广告号的典型手法——恰恰是验证要防的）。
 * 逐个发消息必须走主动通道。
 *
 * <p><b>因此本能力要求 bot token 可用</b>：装配层在启用准入时校验 token（缺失即启动失败），
 * 否则会出现"登记了但验证消息发不出去"→ 用户白白超时被踢的误伤。
 */
public class JoinVerificationService {

    private static final Logger log = LoggerFactory.getLogger(JoinVerificationService.class);

    /** 回调 data 的 action 前缀，需与 {@link VerificationCallbackHandler#action()} 一致。 */
    static final String CALLBACK_ACTION = "verify";

    private static final String PROMPT_TEXT = "欢迎加入。请点击下方按钮完成验证，"
            + "否则将在 %d 秒内被移出本群。";
    private static final String BUTTON_TEXT = "点击验证";

    private final PendingVerificationRegistry registry;
    private final ModerationActionSender sender;
    private final IdHasher idHasher;
    private final Duration timeout;

    public JoinVerificationService(PendingVerificationRegistry registry,
                                   ModerationActionSender sender,
                                   IdHasher idHasher,
                                   Duration timeout) {
        this.registry = registry;
        this.sender = sender;
        this.idHasher = idHasher;
        this.timeout = timeout;
    }

    /**
     * 处理入群事件：逐名新成员登记并发验证消息。
     *
     * <p>机器人（含本 Bot 自己）跳过——否则 Bot 加群时会给自己发验证。
     */
    public void onMembersJoined(Message message) {
        if (message == null || message.getChat() == null
                || message.getNewChatMembers() == null || message.getNewChatMembers().isEmpty()) {
            return;
        }
        Long chatId = message.getChat().getId();
        for (User member : message.getNewChatMembers()) {
            if (member == null || Boolean.TRUE.equals(member.getIsBot())) {
                continue;
            }
            registry.register(chatId, member.getId(), timeout);
            sender.send(buildPrompt(chatId, member.getId()));
            log.info("新成员待验证已登记（chatId={}, 时限={}秒）", chatId, timeout.toSeconds());
        }
    }

    private SendMessage buildPrompt(Long chatId, Long userId) {
        // callbackData 用 userId 的**哈希**而非明文：按钮对全群可见，点开即可读到 data。
        // 项目在日志侧一律对 userId 做哈希（IdHasher），此处同一口径。
        InlineKeyboardButton button = InlineKeyboardButton.builder()
                .text(BUTTON_TEXT)
                .callbackData(CALLBACK_ACTION + ":" + chatId + ":" + idHasher.hash(userId))
                .build();

        return SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(String.format(PROMPT_TEXT, timeout.toSeconds()))
                .replyMarkup(InlineKeyboardMarkup.builder()
                        .keyboard(List.of(new InlineKeyboardRow(button)))
                        .build())
                .build();
    }
}
