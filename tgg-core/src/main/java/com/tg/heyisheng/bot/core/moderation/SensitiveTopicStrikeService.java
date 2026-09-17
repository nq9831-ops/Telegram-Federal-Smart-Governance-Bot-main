package com.tg.heyisheng.bot.core.moderation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 敏感话题违规计数服务（模块九 §10.5）——递进处置的判据。
 *
 * <p>计数口径是「<b>用户 × 群</b>」：同一个人在 A 群屡犯不因在 B 群初犯而清零。
 */
@Service
public class SensitiveTopicStrikeService {

    private final SensitiveTopicStrikeRepository repository;
    private final Clock clock;

    @Autowired
    public SensitiveTopicStrikeService(SensitiveTopicStrikeRepository repository) {
        this(repository, Clock.systemUTC());
    }

    SensitiveTopicStrikeService(SensitiveTopicStrikeRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 记一次违规。
     *
     * @return 该用户在本群的<b>累计次数</b>（首次返回 1）
     */
    @Transactional
    public int record(long chatId, long userId) {
        Instant now = clock.instant();
        repository.upsertIncrement(chatId, userId, now);
        return countOf(chatId, userId);
    }

    /** 只读查询（供测试、运维与「屡犯升级」判定）。0 = 从未违规。 */
    public int countOf(long chatId, long userId) {
        return repository.findByChatIdAndUserId(chatId, userId)
                .map(SensitiveTopicStrike::getStrikeCount)
                .orElse(0);
    }
}
