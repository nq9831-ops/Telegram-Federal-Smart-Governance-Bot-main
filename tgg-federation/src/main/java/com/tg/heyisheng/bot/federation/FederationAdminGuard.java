package com.tg.heyisheng.bot.federation;

import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;

import java.util.HashSet;
import java.util.Set;

/**
 * 联邦管理员判定（模块八）：**全局 userId 白名单**。
 *
 * <p><b>为什么不复用群内 RBAC</b>：现有 {@code Role} / {@code RoleGrantParser} 是**群内**语义
 * （格式 {@code <chatId>:<userId>[:role]}），而联邦管理员是**跨群/全局**角色——
 * 他管理的是联邦，不属于某个群。硬塞进群内模型会让授权源语义错配，
 * 故用独立白名单（配置 {@code tgg.federation.admins}）。
 *
 * <p>门控仍在命令层（handler 内判定），只是授权源不同。
 *
 * <p><b>热生效</b>：生产构造器经 {@link RuntimeConfigService} <b>调用期</b>读取名单，
 * 在配置中心改了即生效，无需重启；另一构造器为静态模式（单测 / 固定名单），行为同改造前。
 */
public class FederationAdminGuard {

    /** 配置键（热读取）。 */
    public static final String KEY = "tgg.federation.admins";

    private final RuntimeConfigService config;
    private final Set<Long> fixedIds;

    /** 热模式：调用期读取。 */
    public FederationAdminGuard(RuntimeConfigService config) {
        this.config = config;
        this.fixedIds = Set.of();
    }

    /** 静态模式（单测 / 固定名单）：立即复制，行为同改造前。 */
    public FederationAdminGuard(Iterable<Long> adminIds) {
        Set<Long> set = new HashSet<>();
        if (adminIds != null) {
            for (Long id : adminIds) {
                if (id != null) {
                    set.add(id);
                }
            }
        }
        this.config = null;
        this.fixedIds = Set.copyOf(set);
    }

    /** 该用户是否为联邦管理员。 */
    public boolean isAdmin(Long userId) {
        return userId != null && adminIds().contains(userId);
    }

    /** 已配置的联邦管理员数量（供装配期告警与测试使用）。 */
    public int size() {
        return adminIds().size();
    }

    private Set<Long> adminIds() {
        return config == null ? fixedIds : config.getCsvIds(KEY);
    }
}
