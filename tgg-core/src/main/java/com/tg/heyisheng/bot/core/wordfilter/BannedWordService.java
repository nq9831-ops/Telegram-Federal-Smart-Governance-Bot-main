package com.tg.heyisheng.bot.core.wordfilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按群违禁词的读写。
 *
 * <p><b>读取 fail-open，但不再「故障即空表」</b>：读词表失败时优先回退<b>上次已知词表</b>——
 * 陈旧但真实，远好过空表（空表在审核路径上等于<b>违禁词全部放行</b>）。
 * 只有本进程从未成功读到过该群词表时，才按空表放行并记 ERROR。
 * （与 {@link com.tg.heyisheng.bot.core.groupconfig.GroupConfigService} 同纪律：见那里的「上次已知配置」。）
 *
 * <p><b>写入 fail-closed-ish</b>：添加/删除失败会抛出并由调用方（命令 handler）感知，
 * 因为那是管理员显式操作，静默失败会让他以为词已加、实际没加。
 *
 * <p><b>热路径与缓存</b>：{@code listWords} 在每条消息上被 {@code BannedWordDetector} 调用
 * （{@code KNOWN-ISSUES} P2#1 记录的「热路径每条消息查一次库」），故与
 * {@code TaughtRuleService} / {@code GroupTopicTagService} 同款：进程内 TTL 缓存
 * （{@link #CACHE_TTL}），写操作后<b>立即失效</b>（加/删词立刻生效，TTL 只是兜底）。
 */
@Service
public class BannedWordService {

    private static final Logger log = LoggerFactory.getLogger(BannedWordService.class);

    /** 与 DB 列宽一致；超出即拒绝，避免运行期 DataTruncation。 */
    static final int MAX_WORD_LENGTH = 255;

    /** 词表缓存 TTL：写后立即失效，TTL 只兜底「本实例未发生的改动」（如另一实例、手工改库）。 */
    static final Duration CACHE_TTL = Duration.ofSeconds(10);

    private final BannedWordRepository repository;
    private final Clock clock;

    /** 进程内 TTL 缓存：chatId → 词表 + 过期时刻。过期条目仍保留，用作读失败时的 last-known 回退。 */
    private final Map<Long, CachedWords> cache = new ConcurrentHashMap<>();

    private record CachedWords(List<String> words, Instant expiresAt) {
    }

    @Autowired
    public BannedWordService(BannedWordRepository repository) {
        this(repository, Clock.systemUTC());
    }

    BannedWordService(BannedWordRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 取某群的词表（原文列表）。
     *
     * <p>缓存命中时不查库（热路径）；未命中则查库并缓存。查库失败时回退上次已知词表，
     * 从未读到过才返回空表。
     */
    @Transactional(readOnly = true)
    public List<String> listWords(Long chatId) {
        if (chatId == null) {
            return List.of();
        }
        Instant now = clock.instant();
        CachedWords cached = cache.get(chatId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.words();
        }
        try {
            List<String> words = repository.findByChatId(chatId).stream().map(BannedWord::getWord).toList();
            cache.put(chatId, new CachedWords(words, now.plus(CACHE_TTL)));
            return words;
        } catch (RuntimeException ex) {
            // 读失败：优先回退「上次已知词表」（陈旧但真实）。空表会让违禁词全部放行，
            // 只有在从未成功读到过时才不得不如此。
            List<String> lastKnown = cached == null ? null : cached.words();
            if (lastKnown == null) {
                log.error("读取违禁词失败（chatId={}），本进程尚无该群词表缓存，本次按无词表放行", chatId, ex);
                return List.of();
            }
            log.error("读取违禁词失败（chatId={}），回退上次已知词表（{} 词）", chatId, lastKnown.size(), ex);
            return lastKnown;
        }
    }

    /**
     * 添加一条词（幂等）。
     *
     * <p><b>幂等由数据库承担</b>：{@code INSERT IGNORE}，命中唯一约束 {@code (chat_id, word)} 时静默跳过。
     *
     * <p><b>为什么不"先查后存 + catch 异常"</b>：那条路在真实数据库上会坏——约束冲突使 Hibernate
     * session 因 flush 失败进入不可用状态，方法即便 catch 了异常，同一事务里后续操作也会失败
     * （详见 {@link BannedWordRepository#insertIgnore}）。改用"不产生异常"的写法才是真幂等。
     *
     * @return true 表示新增成功；false 表示已存在或参数非法
     */
    @Transactional
    public boolean addWord(Long chatId, String word, Long createdBy) {
        String normalized = normalize(word);
        if (chatId == null || normalized == null) {
            return false;
        }
        boolean inserted = repository.insertIgnore(chatId, normalized, createdBy, clock.instant()) > 0;
        cache.remove(chatId); // 词表已变：立即失效，下次读重新加载（「加完立刻生效」）
        return inserted;
    }

    /**
     * 删除一条词。
     *
     * @return true 表示确实删掉了一条；false 表示本群没有该词
     */
    @Transactional
    public boolean removeWord(Long chatId, String word) {
        String normalized = normalize(word);
        if (chatId == null || normalized == null) {
            return false;
        }
        boolean removed = repository.deleteByChatIdAndWord(chatId, normalized) > 0;
        cache.remove(chatId); // 词表已变：立即失效
        return removed;
    }

    /** 归一化：去首尾空白；空串或超长视为非法（返回 null）。 */
    static String normalize(String word) {
        if (word == null) {
            return null;
        }
        String trimmed = word.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_WORD_LENGTH) {
            return null;
        }
        return trimmed;
    }
}
