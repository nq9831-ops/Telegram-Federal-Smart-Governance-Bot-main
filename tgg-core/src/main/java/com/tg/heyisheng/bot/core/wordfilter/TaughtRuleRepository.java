package com.tg.heyisheng.bot.core.wordfilter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 按群教学规则仓库（表 {@code moderation_rules}）。
 *
 * <p><b>无物理删除</b>：规则是审计对象——「谁在什么时候教过什么规则」必须可追溯，
 * 停用走 {@code enabled=false}（同本项目既有的软删纪律）。
 */
public interface TaughtRuleRepository extends JpaRepository<TaughtRule, Long> {

    /** 该群**启用中**的规则（热路径取数入口；按 id 升序保证命中顺序稳定）。 */
    List<TaughtRule> findByChatIdAndEnabledTrueOrderByIdAsc(long chatId);

    /** 该群全部规则（含停用；供管理命令展示）。 */
    List<TaughtRule> findByChatIdOrderByIdAsc(long chatId);

    /** 定位某条规则（幂等写入前的存在性判定）。 */
    Optional<TaughtRule> findByChatIdAndRuleId(long chatId, String ruleId);
}
