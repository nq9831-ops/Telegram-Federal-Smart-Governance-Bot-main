package com.tg.heyisheng.bot.core.moderation;

import com.tg.heyisheng.bot.common.exception.TggException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 群组话题标签服务（模块九 §10.5）：敏感话题分级的**豁免依据**。
 *
 * <p><b>热路径与缓存</b>：{@code tagsOf} 在每条消息上被敏感话题检测调用——若每次都查库，
 * 就是本项目已记录过的「热路径每条消息查一次库」缺陷。故与 {@code TaughtRuleService} 同款：
 * 进程内 TTL 缓存（{@link #CACHE_TTL}），写操作后<b>立即失效</b>（教完立刻生效，TTL 只是兜底）。
 *
 * <p><b>标签规范</b>：统一小写、只允许小写字母/数字/下划线/连字符（见 {@link #requireTag}）。
 * 规范化后比较，避免 {@code Gambling} 与 {@code gambling} 被当成两个标签。
 */
@Service
public class GroupTopicTagService {

    private static final Logger log = LoggerFactory.getLogger(GroupTopicTagService.class);

    /** 标签长度上限（与列宽一致）。 */
    public static final int MAX_TAG_LENGTH = 32;
    /** 缓存 TTL：兜底上限（写后立即失效，通常无感）。 */
    static final Duration CACHE_TTL = Duration.ofSeconds(10);

    private final GroupTopicTagRepository repository;
    private final Clock clock;

    /** 进程内 TTL 缓存：chatId → 标签集合 + 过期时刻。 */
    private final Map<Long, CachedTags> cache = new ConcurrentHashMap<>();

    private record CachedTags(Set<String> tags, Instant expiresAt) {
    }

    @Autowired
    public GroupTopicTagService(GroupTopicTagRepository repository) {
        this(repository, Clock.systemUTC());
    }

    GroupTopicTagService(GroupTopicTagRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 添加一个标签。
     *
     * @return {@code true} 本次新增；{@code false} 该标签已存在（<b>幂等</b>，不是失败）
     */
    @Transactional
    public boolean add(long chatId, String tag, Long actor) {
        String clean = requireTag(tag);
        if (repository.findByChatIdAndTag(chatId, clean).isPresent()) {
            return false;
        }
        repository.save(new GroupTopicTag(chatId, clean, actor, clock.instant()));
        cache.remove(chatId);
        log.info("群话题标签已添加：tag={}", clean);
        return true;
    }

    /** 单独移除一个标签。@return false = 该群没有这个标签 */
    @Transactional
    public boolean remove(long chatId, String tag) {
        String clean = requireTag(tag);
        Optional<GroupTopicTag> found = repository.findByChatIdAndTag(chatId, clean);
        if (found.isEmpty()) {
            return false;
        }
        repository.delete(found.get());
        cache.remove(chatId);
        log.info("群话题标签已移除：tag={}", clean);
        return true;
    }

    /** 热路径入口：该群的全部标签（已缓存）。 */
    public Set<String> tagsOf(long chatId) {
        Instant now = clock.instant();
        CachedTags cached = cache.get(chatId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.tags();
        }
        Set<String> tags = repository.findByChatIdOrderByIdAsc(chatId).stream()
                .map(GroupTopicTag::getTag)
                .collect(Collectors.toUnmodifiableSet());
        cache.put(chatId, new CachedTags(tags, now.plus(CACHE_TTL)));
        return tags;
    }

    /** 该群是否声明了某标签（敏感话题豁免的判据）。 */
    public boolean hasTag(long chatId, String tag) {
        return tag != null && tagsOf(chatId).contains(tag.toLowerCase(Locale.ROOT));
    }

    /** 管理命令用的展示列表（含审计字段）。 */
    public List<GroupTopicTag> listOf(long chatId) {
        return repository.findByChatIdOrderByIdAsc(chatId);
    }

    /** 规范化并校验标签；不合法即抛（命令层如实回显原因）。 */
    static String requireTag(String tag) {
        if (tag == null || tag.isBlank()) {
            throw new TggException("标签不得为空");
        }
        String clean = tag.trim().toLowerCase(Locale.ROOT);
        if (clean.length() > MAX_TAG_LENGTH) {
            throw new TggException("标签过长（上限 " + MAX_TAG_LENGTH + " 字符）");
        }
        if (!clean.matches("[a-z0-9_-]+")) {
            throw new TggException("标签只允许小写字母、数字、下划线与连字符");
        }
        return clean;
    }
}
