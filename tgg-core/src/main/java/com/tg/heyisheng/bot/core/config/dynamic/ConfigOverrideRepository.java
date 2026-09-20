package com.tg.heyisheng.bot.core.config.dynamic;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 配置覆盖仓库。
 *
 * <p>与 {@code AuditLogRepository} 同理，<b>刻意继承空标记接口 {@link Repository} 而非
 * {@code JpaRepository}</b>——只声明真正需要的方法，把仓库面收窄到可读可审的规模。
 * 与审计表不同的是：配置覆盖<b>本就允许删除</b>（删除＝停用一项覆盖，回落到默认），
 * 故此处显式提供 {@code deleteById}。
 */
public interface ConfigOverrideRepository extends Repository<ConfigOverride, String> {

    /** 全部覆盖（启动加载与总览用）。 */
    List<ConfigOverride> findAll();

    /** 按配置键查。 */
    Optional<ConfigOverride> findById(String configKey);

    /** 新增或更新一条覆盖（主键存在即更新）。 */
    ConfigOverride save(ConfigOverride override);

    /** 删除一条覆盖（回落到环境变量/默认）。 */
    void deleteById(String configKey);
}
