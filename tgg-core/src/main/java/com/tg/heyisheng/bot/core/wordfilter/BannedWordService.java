package com.tg.heyisheng.bot.core.wordfilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 按群违禁词的读写。
 *
 * <p><b>读取 fail-open</b>：读词表失败时按「无词表」放行并记 ERROR——
 * 词表是**增强**而非**门禁**，数据库抖动不应让全群消息都被拦下或抛出异常。
 * （与 {@link com.tg.heyisheng.bot.core.groupconfig.GroupConfigService} 同纪律。）
 *
 * <p><b>写入 fail-closed-ish</b>：添加/删除失败会抛出并由调用方（命令 handler）感知，
 * 因为那是管理员显式操作，静默失败会让他以为词已加、实际没加。
 */
@Service
public class BannedWordService {

    private static final Logger log = LoggerFactory.getLogger(BannedWordService.class);

    /** 与 DB 列宽一致；超出即拒绝，避免运行期 DataTruncation。 */
    static final int MAX_WORD_LENGTH = 255;

    private final BannedWordRepository repository;

    public BannedWordService(BannedWordRepository repository) {
        this.repository = repository;
    }

    /** 取某群的词表（原文列表）。失败时返回空表并记 ERROR。 */
    @Transactional(readOnly = true)
    public List<String> listWords(Long chatId) {
        if (chatId == null) {
            return List.of();
        }
        try {
            return repository.findByChatId(chatId).stream().map(BannedWord::getWord).toList();
        } catch (RuntimeException ex) {
            log.error("读取违禁词失败（chatId={}），本次按无词表放行", chatId, ex);
            return List.of();
        }
    }

    /**
     * 添加一条词（幂等）。
     *
     * @return true 表示新增成功；false 表示已存在或参数非法
     */
    @Transactional
    public boolean addWord(Long chatId, String word, Long createdBy) {
        String normalized = normalize(word);
        if (chatId == null || normalized == null) {
            return false;
        }
        if (repository.existsByChatIdAndWord(chatId, normalized)) {
            return false;
        }
        try {
            repository.save(new BannedWord(chatId, normalized, createdBy));
            return true;
        } catch (DataIntegrityViolationException ex) {
            // 并发下两个请求同时通过了上面的 exists 检查，第二个会撞 (chat_id, word) 唯一约束。
            // 语义上等同"已存在"，因此返回 false，而不是把异常抛给命令层
            // （那会让 /addword 直接失败，破坏本方法宣称的幂等契约）。
            log.debug("并发添加违禁词撞唯一约束，按已存在处理（chatId={}）", chatId);
            return false;
        }
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
        return repository.deleteByChatIdAndWord(chatId, normalized) > 0;
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
