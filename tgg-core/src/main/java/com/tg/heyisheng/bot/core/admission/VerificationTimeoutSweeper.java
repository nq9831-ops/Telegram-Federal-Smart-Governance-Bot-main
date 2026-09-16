package com.tg.heyisheng.bot.core.admission;

import com.tg.heyisheng.bot.core.moderation.ModerationActionSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;

import java.time.Instant;

/**
 * 超时未验证者移出：定时扫描 {@link PendingVerificationRegistry} 中的过期登记。
 *
 * <p><b>为什么用定时扫描而不是惰性检查</b>：惰性检查只在"该群又有人发言"时才触发——
 * 一个只发广告、入群后不说话的账号永远不会触发，恰是验证要防的对象。
 *
 * <p><b>单实例假设</b>：定时任务在多实例部署下会各扫各的（重复踢同一人），
 * 与内存限流器同样的限制；生产多实例需改为分布式锁或共享存储（见部署清单）。
 */
public class VerificationTimeoutSweeper {

    private static final Logger log = LoggerFactory.getLogger(VerificationTimeoutSweeper.class);

    private final PendingVerificationRegistry registry;
    private final ModerationActionSender sender;

    public VerificationTimeoutSweeper(PendingVerificationRegistry registry,
                                      ModerationActionSender sender) {
        this.registry = registry;
        this.sender = sender;
    }

    /** 暂时移出的时长：届时 Telegram 自动解禁，用户可重新入群并再次验证。 */
    static final int KICK_SECONDS = 60;

    /** 周期性扫描过期登记并移出。 */
    @Scheduled(fixedDelayString = "${tgg.admission.sweep-interval-ms:15000}")
    public void sweep() {
        for (PendingVerificationRegistry.Member member : registry.drainExpired()) {
            log.info("验证超时，移出成员（chatId={}）", member.chatId());
            // 必须设 untilDate：不设即**永久封禁**，用户再也进不来——那是"封禁"而不是"移出"，
            // 与验证的语义（超时就请重来一次）不符。设了它会到时自动解禁。
            int until = (int) (Instant.now().getEpochSecond() + KICK_SECONDS);
            sender.send(BanChatMember.builder()
                    .chatId(member.chatId())
                    .userId(member.userId())
                    .untilDate(until)
                    .build());
        }
    }
}
