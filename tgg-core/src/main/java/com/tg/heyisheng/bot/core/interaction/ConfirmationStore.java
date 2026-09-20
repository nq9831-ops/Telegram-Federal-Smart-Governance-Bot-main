package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.Confirm;
import com.tg.heyisheng.bot.core.dispatch.ConfirmationRequests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待确认操作的登记簿（{@link ConfirmationRequests} 的内存实现）。
 *
 * <p><b>为什么需要它</b>：确认卡与「用户点确认」是两次独立的 HTTP 回调，中间没有任何共享状态。
 * 令牌把两者连起来，并承载三条安全性质：
 * <ol>
 *   <li><b>一次性</b>——消费即删，重放无效；</li>
 *   <li><b>限发起者</b>——卡片对全群可见，他人点击不得代为确认（同准入验证的「不是你的按钮」）；</li>
 *   <li><b>有期限</b>——超时作废，避免旧卡在群里长期挂着等人手滑。</li>
 * </ol>
 *
 * <p><b>刻意是进程内内存态</b>：本项目未引 Redis，与内存限流器、准入登记同一取舍——
 * 单实例部署下语义正确；多实例时「A 实例发的卡在 B 实例点」会失效，TTL 内重发即可。
 * 这是设计文档 §7.2 已记录的已知边界，不是疏漏。
 */
public class ConfirmationStore implements ConfirmationRequests {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationStore.class);

    /** 待确认操作的有效期：够读完卡片再点，又不至于让旧卡长期可用。 */
    static final Duration TTL = Duration.ofSeconds(120);

    /**
     * 待确认条目上限。
     *
     * <p>超限时的处理是**淘汰最旧的一条**而不是拒绝新请求：拒绝会让用户卡在一个他看不见原因的错误上，
     * 而淘汰最旧的只会让某张旧卡失效（用户重发一次即可）。上限本身是防「反复触发危险命令塞满内存」。
     */
    static final int MAX_PENDING = 500;

    private final Map<String, PendingAction> pending = new ConcurrentHashMap<>();
    private final Clock clock;

    public ConfirmationStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean requiresConfirmation(UpdateContext ctx, Confirm mode) {
        // 已确认的本次执行直接放行——标记只可能由桥在「令牌成功消费」之后挂上，外部请求伪造不出
        if (ctx.find(ConfirmationGranted.class).isPresent()) {
            return false;
        }
        return switch (mode) {
            case NEVER -> false;
            case ALWAYS -> true;
            // 判据是「本次有没有带操作数」，而不是「这条命令是否声明需要参数」——
            // 注册表不掌握后者，靠它判断会引入一份随新命令悄悄过期的清单
            case WHEN_ARGS -> ctx.commandArgs().filter(args -> !args.isBlank()).isPresent();
        };
    }

    @Override
    public String issue(UpdateContext ctx) {
        purgeExpired();
        if (pending.size() >= MAX_PENDING) {
            evictOldest();
        }
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        pending.put(nonce, new PendingAction(ctx.chatId(), ctx.userId(),
                ctx.command().orElse(null), ctx.commandArgs().orElse(null),
                clock.instant().plus(TTL)));
        return nonce;
    }

    /**
     * 消费一个令牌。
     *
     * @param clickerId 点击者；与发起者不符时返回空<b>且不删除</b>——
     *                  既防代点，也防有人用乱点把别人的确认「消耗掉」
     * @return 命中且未过期、且点击者就是发起者时的待确认操作
     */
    public Optional<PendingAction> consume(String nonce, Long clickerId) {
        if (nonce == null) {
            return Optional.empty();
        }
        PendingAction action = pending.get(nonce);
        if (action == null) {
            return Optional.empty();
        }
        if (action.expiresAt().isBefore(clock.instant())) {
            pending.remove(nonce);
            return Optional.empty();
        }
        if (clickerId == null || !clickerId.equals(action.userId())) {
            log.warn("确认令牌的点击者与发起者不符，已拒绝（令牌保留）");
            return Optional.empty();
        }
        pending.remove(nonce); // 一次性：消费即删
        return Optional.of(action);
    }

    /** 当前待确认条数（诊断与测试用）。 */
    public int size() {
        purgeExpired();
        return pending.size();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        pending.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private void evictOldest() {
        pending.entrySet().stream()
                .min(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                .map(Map.Entry::getKey)
                .ifPresent(oldest -> {
                    pending.remove(oldest);
                    log.warn("待确认操作已达上限 {}，淘汰最旧的一条", MAX_PENDING);
                });
    }

    /** 一次待确认的操作：谁、在哪个群、要执行什么、何时作废。 */
    public record PendingAction(Long chatId, Long userId, String command, String args, Instant expiresAt) {
    }
}
