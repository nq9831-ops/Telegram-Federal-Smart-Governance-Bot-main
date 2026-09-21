package com.tg.heyisheng.bot.admin.identity;

import com.tg.heyisheng.bot.admin.AdminProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;

/**
 * 超管引导创建（模块十一 · 账号与权限模型）。
 *
 * <p><b>安全不变量——超管只能由环境变量引导，任何 API 都不得创建超管</b>：
 * 若允许 API 建超管，「谁能建超管」立刻成为新的提权口。故创建入口只有这一处：
 * 启动期读 {@code tgg.admin.super-user} + {@code tgg.admin.super-password-hash}，
 * 若该登录名尚不存在则创建。运行时无任何路径能产生第二个超管。
 *
 * <p><b>幂等</b>：登录名已存在即跳过——重启不会重复创建，也不会覆盖已改过的密码。
 *
 * <p><b>密码只接受哈希</b>（{@link PasswordHasher} 的 {@code pbkdf2$...} 格式），
 * 不读明文，避免明文密码进环境变量/进程表。生成方式见部署文档。
 */
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminAccountRepository accounts;
    private final AdminProperties properties;
    private final Clock clock;

    public AdminBootstrap(AdminAccountRepository accounts, AdminProperties properties, Clock clock) {
        this.accounts = accounts;
        this.properties = properties;
        this.clock = clock;
    }

    /** 启动期引导：环境变量已给且登录名不存在时创建超管。 */
    @PostConstruct
    public void bootstrapSuperAdmin() {
        String username = properties.getSuperUser();
        String hash = properties.getSuperPasswordHash();
        if (username == null || username.isBlank() || hash == null || hash.isBlank()) {
            log.warn("未配置超管引导（tgg.admin.super-user / tgg.admin.super-password-hash）："
                    + "不会创建超管账号。需要超管时请设这两个环境变量后重启"
                    + "（password-hash 须为 PasswordHasher 生成的 pbkdf2$... 串）。");
            return;
        }
        String name = username.trim();
        if (accounts.existsByUsername(name)) {
            log.info("超管账号「{}」已存在，跳过引导创建。", name);
            return;
        }
        accounts.save(new AdminAccount(name, hash.trim(), AdminRole.SUPER_ADMIN, Instant.now(clock)));
        log.info("已按环境变量引导创建超管账号：「{}」。", name);
    }
}
