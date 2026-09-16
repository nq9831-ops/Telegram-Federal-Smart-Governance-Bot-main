package com.tg.heyisheng.bot.core.groupconfig;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 群组配置仓库。
 *
 * <p>本阶段只需要按主键查/存；复杂查询等业务需要时再加。
 */
public interface GroupConfigRepository extends JpaRepository<GroupConfig, Long> {
}
