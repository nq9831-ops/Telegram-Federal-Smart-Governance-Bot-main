package com.tg.heyisheng.bot.core.membership;

import com.tg.heyisheng.bot.common.util.IdHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberBanned;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberLeft;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMemberUpdated;

import java.time.Clock;
import java.time.Instant;

/**
 * 成员入群时间采集（模块九 §10.3「入群时长 ≥30 天」门槛的数据源）。
 *
 * <p><b>为什么只能被动采集</b>：Telegram 没有「查某成员入群时间」的接口，只有 {@code chat_member}
 * 更新会推送状态变更。故本类只做两件事：观察到「在群」就记一行（<b>不覆盖</b>已有），
 * 观察到「离群」就删一行（数据最小化：表内只留当前在群成员）。
 *
 * <p><b>两个最容易写错的点（都有测试钉住）</b>：
 * <ol>
 *   <li>被更新的成员是 {@code new_chat_member.getUser()}，<b>不是</b> {@code from}
 *       ——后者是<b>触发者</b>（可能是踢人的管理员）。用错会把管理员的入群时间记成被踢者的。</li>
 *   <li>{@code chat_member} 更新覆盖<b>所有</b>状态变化（晋升管理员、被禁言都会触发），
 *       不只是加入/离开。故「在群」分支必须走 {@code INSERT IGNORE}——否则一次晋升就把入群时间
 *       刷新成当天，「入群时长」门槛被无声绕过。</li>
 * </ol>
 *
 * <p><b>隐私</b>：日志中的群/成员标识一律经 {@link IdHasher} 哈希，与全项目口径一致；
 * 本类不记录任何用户名、昵称或邀请来源。
 */
public class MemberJoinRecorder {

    private static final Logger log = LoggerFactory.getLogger(MemberJoinRecorder.class);

    private final MemberJoinObservationRepository repository;
    private final IdHasher idHasher;
    private final Clock clock;

    public MemberJoinRecorder(MemberJoinObservationRepository repository, IdHasher idHasher, Clock clock) {
        this.repository = repository;
        this.idHasher = idHasher;
        this.clock = clock;
    }

    /**
     * 处理一条 {@code chat_member} 更新。
     *
     * <p>刻意不抛出：采集是增强能力，不该让上游分发链因它中断（与其他「非必要环节」一致）。
     * 但字段缺失时<b>什么都不做</b>——不猜、不写半条记录。
     */
    @Transactional
    public void onChatMemberUpdated(ChatMemberUpdated update) {
        if (update == null) {
            return;
        }
        Chat chat = update.getChat();
        ChatMember newMember = update.getNewChatMember();
        if (chat == null || chat.getId() == null || newMember == null) {
            return;
        }
        User user = newMember.getUser();
        if (user == null || user.getId() == null) {
            return;
        }

        long chatId = chat.getId();
        long userId = user.getId();
        Instant observedAt = clock.instant();

        if (isAway(newMember)) {
            long deleted = repository.deleteByChatIdAndUserId(chatId, userId);
            if (deleted > 0) {
                log.info("成员退群，已删除入群记录：chatHash={} userHash={}",
                        idHasher.hash(chatId), idHasher.hash(userId));
            }
            return;
        }

        Instant joinedAt = toInstant(update.getDate(), observedAt);
        int inserted = repository.insertIfAbsent(chatId, userId, joinedAt, observedAt);
        if (inserted > 0) {
            log.info("记录入群时间：chatHash={} userHash={} joinedAt={}",
                    idHasher.hash(chatId), idHasher.hash(userId), joinedAt);
        }
        // inserted == 0：该成员已有行（本次是晋升/禁言等状态变化）——
        // 刻意既不记日志也不刷新 joined_at，入群时间保持最早一次观察。
    }

    /**
     * 「不在群」= 已离开（{@code left}）或已被踢/封（{@code kicked}）。
     *
     * <p>用<b>类型</b>而非 {@code getStatus()} 字符串判定：库里的 {@code MemberStatus} 只列了
     * CREATOR/ADMINISTRATOR/MEMBER/LEFT/KICKED 五个常量，<b>没有 RESTRICTED</b>，
     * 靠字符串白名单会把受限成员误判成离群。类型由编译期保证，且其余状态（含受限）自动归入「在群」。
     */
    private static boolean isAway(ChatMember member) {
        return member instanceof ChatMemberLeft || member instanceof ChatMemberBanned;
    }

    /** Telegram 给的是 Unix 秒；缺失时降级为本地时刻——宁可有记录，也不要因单一字段缺失丢掉整个事件。 */
    private static Instant toInstant(Integer unixSeconds, Instant fallback) {
        return unixSeconds == null ? fallback : Instant.ofEpochSecond(unixSeconds);
    }
}
