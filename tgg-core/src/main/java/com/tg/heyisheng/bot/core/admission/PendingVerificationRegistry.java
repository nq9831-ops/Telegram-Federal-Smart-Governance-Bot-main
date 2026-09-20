package com.tg.heyisheng.bot.core.admission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待验证登记（**内存**，不落库）。
 *
 * <p><b>为什么是内存</b>：登记项含 userId，落库即引入个人数据持久化——本项目隐私优先，
 * 而验证是短暂状态（默认 2 分钟），重启丢失的代价是"用户重新入群/重新触发"，可接受。
 *
 * <p><b>为什么用 record 而非 "chat:user" 字符串 key</b>：字符串 key 取出时得再解析回来，
 * 而 {@code split(":")} 会丢尾部空串（须用 {@code split(":", -1)} 才保真）。
 * 用类型化的 key 从根上避免这类解析。
 *
 * <p><b>时钟可注入</b>：超时判定必须能在测试里推进，而不是靠 sleep。
 */
public class PendingVerificationRegistry {

    /** 群 + 用户组成登记键。 */
    public record Member(Long chatId, Long userId) {
    }

    private final Map<Member, Instant> deadlines = new ConcurrentHashMap<>();
    private final Clock clock;

    public PendingVerificationRegistry(Clock clock) {
        this.clock = clock;
    }

    /** 登记一名待验证成员。 */
    public void register(Long chatId, Long userId, Duration timeout) {
        if (chatId == null || userId == null) {
            return;
        }
        deadlines.put(new Member(chatId, userId), clock.instant().plus(timeout));
    }

    /** 该成员是否仍在待验证状态（且未过期）。 */
    public boolean isPending(Long chatId, Long userId) {
        Instant deadline = deadlines.get(new Member(chatId, userId));
        return deadline != null && !deadline.isBefore(clock.instant());
    }

    /**
     * 标记验证通过：移除登记。
     *
     * <p><b>必须同时检查是否过期</b>——只判断"登记还在不在"会放过超时点击：
     * 过期条目在 {@link #peekExpired()} 被扫走之前仍留在 map 里，点击就会"通过"，
     * 等于超时机制形同虚设。（此缺陷由 {@code expiredOrRepeatedClickIsRejected} 实测抓出。）
     *
     * @return true 表示确有**未过期**的登记被移除；过期或重复点击返回 false
     */
    public boolean markVerified(Long chatId, Long userId) {
        Member member = new Member(chatId, userId);
        Instant deadline = deadlines.get(member);
        if (deadline == null) {
            return false;
        }
        Instant removed = deadlines.remove(member);
        return removed != null && !deadline.isBefore(clock.instant());
    }

    /**
     * 列出当前已过期的登记，<b>但不移除</b>。
     *
     * <p>与「取出即移除」的区别是要害：移出成员是<b>主动 API 调用、可能失败</b>
     * （无 token 时空实现、或临时网络/权限错误）。若先移除登记再发送，失败就无法重试，
     * 成员会滞留在群里且<b>永不再被移出</b>。故调用方应「先发送、成功后才 {@link #remove}」。
     */
    public List<Member> peekExpired() {
        Instant now = clock.instant();
        return deadlines.entrySet().stream()
                .filter(entry -> entry.getValue().isBefore(now))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 移除一条登记（移出动作成功确认后调用）。 */
    public void remove(Member member) {
        deadlines.remove(member);
    }

    /** 当前待验证数量（诊断与测试用）。 */
    public int size() {
        return deadlines.size();
    }
}
