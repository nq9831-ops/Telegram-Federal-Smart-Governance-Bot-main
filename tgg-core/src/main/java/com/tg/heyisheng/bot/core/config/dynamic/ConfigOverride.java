package com.tg.heyisheng.bot.core.config.dynamic;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 运行期配置覆盖（{@code config_override} 表）。
 *
 * <p><b>只存非密钥键</b>——是否可写由 {@link ConfigKey#writable()} 在写入前拦截；
 * 故明文存储可接受。
 *
 * <p><b>为什么是「覆盖」而不是「配置本体」</b>：本表存的是相对环境变量/{@code application.yml} 的
 * **最高优先级覆盖**。删掉一行即回落到既有默认，不会让配置凭空消失——这使「回滚一次误改」
 * 与「停用一项覆盖」是同一个动作。
 */
@Entity
@Table(name = "config_override")
public class ConfigOverride {

    /** Spring 属性名；本表主键（每键至多一条覆盖）。 */
    @Id
    @Column(name = "config_key", length = 128)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 1024)
    private String configValue;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    /** JPA 要求的无参构造器（protected，避免业务代码误用）。 */
    protected ConfigOverride() {
    }

    public ConfigOverride(String configKey, String configValue, Instant updatedAt, Long updatedBy) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public String getConfigKey() {
        return configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }
}
