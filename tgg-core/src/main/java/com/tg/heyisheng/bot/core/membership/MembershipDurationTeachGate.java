package com.tg.heyisheng.bot.core.membership;

import com.tg.heyisheng.bot.core.wordfilter.TeachGate;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * 「入群时长」教学门槛（模块九 §10.3 原文三条门槛中的第三条）。
 *
 * <p><b>它为什么存在</b>：原文要求「入群时长 ≥30 天」——即刚进群的人不能立刻改本群规则。
 * 数据来自 {@link MemberJoinObservation}（{@code chat_member} 更新被动采集）。
 *
 * <p><b>⚠️ 无记录时的口径：放行（fail-open），这是刻意的</b>
 * <ul>
 *   <li>Telegram <b>没有</b>「查某人何时入群」的接口，只能靠 bot 收到 {@code chat_member} 更新时被动记录。
 *       因此 <b>bot 成为群管理员之前就已入群的成员不会有任何记录</b>——而且这个状态是永久的，
 *       他们的入群时间在 Telegram 侧也取不回来。</li>
 *   <li>若把「无记录」判为拒绝，等于<b>永久误伤所有历史成员</b>（批量、不可申诉、且用户无法理解原因）。</li>
 *   <li>故采用「<b>已知不足则拒绝，未知则放行</b>」：采集生效后入群的新成员会被正确拦住
 *       （有记录、时长可判），历史成员不受影响。</li>
 * </ul>
 *
 * <p><b>这条门槛的实际强度取决于部署</b>：bot 若不是群管理员，或 webhook 的
 * {@code allowed_updates} 未包含 {@code chat_member}，则一条记录都不会产生、门槛恒为放行。
 * 部署方须确保 bot 为群管理员，且 webhook 的 {@code allowed_updates} 含 {@code chat_member}。
 */
public class MembershipDurationTeachGate implements TeachGate {

    private final MemberJoinObservationRepository repository;
    private final Clock clock;
    private final Duration minimum;

    public MembershipDurationTeachGate(MemberJoinObservationRepository repository,
                                       Clock clock,
                                       Duration minimum) {
        this.repository = repository;
        this.clock = clock;
        this.minimum = minimum;
    }

    @Override
    public Optional<String> rejectionFor(long chatId, Long userId) {
        if (userId == null) {
            return Optional.of("无法识别你的身份。");
        }
        Optional<MemberJoinObservation> observation = repository.findByChatIdAndUserId(chatId, userId);
        if (observation.isEmpty()) {
            // 未知 = 放行。理由见类注释：拒绝会让所有历史成员永久失去教学资格。
            return Optional.empty();
        }
        Duration observed = Duration.between(observation.get().getJoinedAt(), clock.instant());
        if (observed.compareTo(minimum) >= 0) {
            return Optional.empty();
        }
        // 不含该成员的入群时刻（那是个人数据，且回显给管理员的文案不该带别人的时间线）
        return Optional.of("本群教学要求入群满 " + minimum.toDays() + " 天（你尚未满足）。");
    }
}
