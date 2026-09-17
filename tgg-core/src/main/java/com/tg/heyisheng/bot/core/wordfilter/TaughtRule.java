package com.tg.heyisheng.bot.core.wordfilter;

import com.tg.heyisheng.bot.core.moderation.ModerationRule;
import com.tg.heyisheng.bot.core.moderation.RiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一条<b>按群教学规则</b>（表 {@code moderation_rules}，模块九 §10.3 的 {@code /teach}）。
 *
 * <p>表结构由 Flyway <b>V8</b> 管理，JPA 侧 {@code ddl-auto: validate}。
 *
 * <p><b>为什么与 {@link ModerationRule} 是两个类型</b>：那个是<b>不可变的值对象</b>（record，
 * 已被编译成 {@code Pattern}，热路径直接吃它）；本类是<b>持久化实体</b>（可停用、可重定义、
 * 带审计字段）。把两者合一会让热路径对象背上 JPA 生命周期，也会让「内置规则」被迫持久化
 * ——内置规则是代码资产，不该进库。二者由 {@link #toRule()} 单向转换。
 *
 * <p><b>不存消息正文</b>：表里只有正则与元信息。规则是<b>模板</b>，不是内容。
 */
@Entity
@Table(name = "moderation_rules")
public class TaughtRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private long chatId;

    @Column(name = "rule_id", nullable = false, length = 64)
    private String ruleId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "regex", nullable = false, length = 512)
    private String regex;

    @Column(name = "risk_level", nullable = false, length = 16)
    private String riskLevel;

    @Column(name = "hard_line", nullable = false)
    private boolean hardLine;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TaughtRule() {
    }

    public TaughtRule(long chatId, String ruleId, String name, String regex,
                      RiskLevel riskLevel, boolean hardLine, Long createdBy, Instant now) {
        this.chatId = chatId;
        this.ruleId = ruleId;
        this.name = name;
        this.regex = regex;
        this.riskLevel = riskLevel.name();
        this.hardLine = hardLine;
        this.enabled = true;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 停用（不删除：规则是审计对象，停用后仍可追溯谁在什么时候教过什么）。 */
    public void disable(Instant now) {
        this.enabled = false;
        this.updatedAt = now;
    }

    /** 重新定义（同群同 ruleId 再教一次 = 覆盖内容）。 */
    public void redefine(String name, String regex, RiskLevel riskLevel, boolean hardLine, Instant now) {
        this.name = name;
        this.regex = regex;
        this.riskLevel = riskLevel.name();
        this.hardLine = hardLine;
        this.enabled = true;
        this.updatedAt = now;
    }

    /**
     * 转成热路径用的值对象。
     *
     * <p><b>正则在调用方已校验过</b>（{@code TaughtRuleService} 入库前编译验证）——
     * 本方法不吞 {@code PatternSyntaxException}：若库里存在非法正则（手改库/降级写入），
     * 宁可让它在上层被记录并按「该规则不可用」跳过，也不要静默造出一个永不命中的规则。
     */
    public ModerationRule toRule() {
        return ModerationRule.of(ruleId, name, regex, RiskLevel.valueOf(riskLevel));
    }

    public Long getId() {
        return id;
    }

    public long getChatId() {
        return chatId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getName() {
        return name;
    }

    public String getRegex() {
        return regex;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public boolean isHardLine() {
        return hardLine;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
