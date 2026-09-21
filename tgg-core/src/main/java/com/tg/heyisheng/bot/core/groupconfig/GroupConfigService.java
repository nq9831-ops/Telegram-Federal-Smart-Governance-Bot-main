package com.tg.heyisheng.bot.core.groupconfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群组配置读写。
 *
 * <p><b>数据库故障时不再「故障即默认启用」</b>：读配置失败时优先回退<b>上次已知配置</b>——
 * 这一点对<b>关闭态</b>的群至关重要：群主 {@code /disable} 表达的意愿不能因 DB 抖动被
 * {@code defaultFor(enabled=true)} 覆盖（那等于把「失效行为」做成了「恢复响应」）。
 * 只有本进程从未成功读到过该群配置时，才按默认（启用）放行并记 ERROR。
 *
 * <p>仍保持「增强而非门禁」的纪律，但收窄了故障语义：见 {@code KNOWN-ISSUES} 的定向审计段。
 *
 * <p><b>例外</b>：若将来某配置项是安全门禁（如黑名单判定），那条路径须改为
 * fail-close。届时单独设计，不在此处一刀切。
 *
 * <p><b>热路径与缓存</b>：{@code findOrDefault} 在每条消息上被 {@code GroupConfigMiddleware} 调用，
 * 故与 {@code TaughtRuleService} / {@code BannedWordService} 同款：进程内 TTL 缓存
 * （{@link #CACHE_TTL}），写操作后<b>立即失效</b>（开关翻转立刻生效，TTL 只是兜底）。
 */
@Service
public class GroupConfigService {

    private static final Logger log = LoggerFactory.getLogger(GroupConfigService.class);

    /** 配置缓存 TTL：写后立即失效，TTL 只兜底「本实例未发生的改动」（如另一实例、手工改库）。 */
    static final Duration CACHE_TTL = Duration.ofSeconds(10);

    private final GroupConfigRepository repository;
    private final Clock clock;

    /** 进程内 TTL 缓存：chatId → 配置视图 + 过期时刻。过期条目仍保留，用作读失败时的 last-known 回退。 */
    private final Map<Long, CachedView> cache = new ConcurrentHashMap<>();

    private record CachedView(GroupConfigView view, Instant expiresAt) {
    }

    @Autowired
    public GroupConfigService(GroupConfigRepository repository) {
        this(repository, Clock.systemUTC());
    }

    GroupConfigService(GroupConfigRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 查询群组配置；未登记的群组返回默认配置（不视为错误）。
     *
     * <p>缓存命中时不查库（热路径）；未命中则查库并缓存。查库失败时回退上次已知配置，
     * 从未读到过才回退默认配置。
     */
    @Transactional(readOnly = true)
    public GroupConfigView findOrDefault(Long chatId) {
        if (chatId == null) {
            return GroupConfigView.defaultFor(null);
        }
        Instant now = clock.instant();
        CachedView cached = cache.get(chatId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.view();
        }
        try {
            GroupConfigView view = repository.findById(chatId)
                    .map(c -> new GroupConfigView(c.getChatId(), c.getTitle(), c.isEnabled()))
                    .orElseGet(() -> GroupConfigView.defaultFor(chatId));
            cache.put(chatId, new CachedView(view, now.plus(CACHE_TTL)));
            return view;
        } catch (RuntimeException ex) {
            // 读失败：优先回退「上次已知配置」——关闭态的群必须保持关闭，不能被默认启用覆盖。
            GroupConfigView lastKnown = cached == null ? null : cached.view();
            if (lastKnown == null) {
                log.error("读取群组配置失败（chatId={}），本进程尚无该群配置缓存，本次按默认配置放行", chatId, ex);
                return GroupConfigView.defaultFor(chatId);
            }
            log.error("读取群组配置失败（chatId={}），回退上次已知配置（enabled={}）", chatId,
                    lastKnown.enabled(), ex);
            return lastKnown;
        }
    }

    /**
     * 登记群组（幂等：已存在则只更新标题）。
     *
     * <p><b>原子性</b>：走原生 upsert（{@link GroupConfigRepository#upsertOnRegister}），
     * 取代「先 findById 判空再 save」——后者在并发首次登记同一个群时会撞主键约束。
     *
     * <p><b>调用面</b>：当前生产链路不调用本方法（配置行由 {@link #setEnabled} 按需创建）；
     * 它供集成测试与管理入口「登记群组」使用。
     */
    @Transactional
    public GroupConfig register(Long chatId, String title) {
        repository.upsertOnRegister(chatId, title, clock.instant());
        cache.remove(chatId); // 配置已变：立即失效
        return repository.findById(chatId)
                .orElseThrow(() -> new IllegalStateException("upsert 后应能读到群组配置：" + chatId));
    }

    /**
     * 开关某群的自动化能力。
     *
     * <p><b>原子性</b>：走 {@link GroupConfigRepository#upsertEnabled} 单条原生语句。
     * 此前的「findById 判空 → save」在<b>首次</b>为同一个群并发写入时会撞主键约束
     * （{@code /enable} 与 {@code /disable} 同时到达、或重复点击）。
     */
    @Transactional
    public GroupConfigView setEnabled(Long chatId, boolean enabled) {
        repository.upsertEnabled(chatId, enabled, clock.instant());
        cache.remove(chatId); // 开关翻转必须立刻生效，不能等 TTL
        GroupConfig config = repository.findById(chatId)
                .orElseThrow(() -> new IllegalStateException("upsert 后应能读到群组配置：" + chatId));
        return new GroupConfigView(config.getChatId(), config.getTitle(), config.isEnabled());
    }
}
