package com.tg.heyisheng.bot.admin.identity;

import com.tg.heyisheng.bot.core.audit.ActorType;
import com.tg.heyisheng.bot.core.platform.PlatformGrantSource;
import com.tg.heyisheng.bot.core.platform.PlatformPermission;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 账号管理（模块十一 · 权限模型）——超管建操作员、停用、分配能力、重置密码、强制下线。
 *
 * <p><b>安全不变量（违反即视为缺陷）</b>：
 * <ol>
 *   <li><b>只有超管能调用本服务</b>（判定在控制器层：非超管一律 403）。故「操作员给自己或他人加权限」
 *       这条提权路在入口即被堵死——写入侧只有超管一种主体。</li>
 *   <li><b>不可创建超管</b>：{@link #createOperator} 只能建 {@link AdminRole#OPERATOR}——
 *       否则「谁能建超管」是新提权口。超管仅由环境变量引导创建（{@link AdminBootstrap}）。</li>
 *   <li><b>不可改动超管账号</b>：超管天然全权，其能力不可分配、不可停用——避免把自己降级成
 *       一个「能力被收空的超管」而锁死系统。</li>
 *   <li><b>{@link PlatformPermission#GRANT_MANAGE} 不可授予</b>（{@link PlatformGrantSource} 已拒绝）。</li>
 * </ol>
 */
public class AccountAdminService {

    private final AdminAccountRepository accounts;
    private final PlatformGrantSource grants;
    private final AdminAuthService auth;
    private final Clock clock;

    public AccountAdminService(AdminAccountRepository accounts, PlatformGrantSource grants,
                               AdminAuthService auth, Clock clock) {
        this.accounts = accounts;
        this.grants = grants;
        this.auth = auth;
        this.clock = clock;
    }

    /** 账号视图（含其能力清单）。 */
    public record AccountView(Long id, String username, AdminRole role, AdminStatus status,
                              Set<PlatformPermission> permissions) {
    }

    /** 列出全部账号（超管）；超管的能力显示为「全有」。 */
    public List<AccountView> list() {
        List<AccountView> views = new ArrayList<>();
        for (AdminAccount account : accounts.findAllByOrderByIdAsc()) {
            Set<PlatformPermission> perms = account.isSuperAdmin()
                    ? Set.of(PlatformPermission.values())
                    : grants.permissionsOf(ActorType.ADMIN_ACCOUNT, account.getId());
            views.add(new AccountView(account.getId(), account.getUsername(), account.getRole(),
                    account.getStatus(), perms));
        }
        return views;
    }

    /** 建操作员（只能 OPERATOR）。 */
    public AdminAccount createOperator(String username, String rawPassword) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("登录名不能为空");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (accounts.existsByUsername(name)) {
            throw new IllegalArgumentException("登录名已存在：" + name);
        }
        return accounts.save(new AdminAccount(name, PasswordHasher.hash(rawPassword),
                AdminRole.OPERATOR, clock.instant()));
    }

    /** 停用 / 启用（停用即吊销其全部会话）。 */
    public void setStatus(Long accountId, AdminStatus status) {
        AdminAccount account = require(accountId);
        if (account.isSuperAdmin()) {
            throw new IllegalArgumentException("不可停用超管账号——若需变更请先用环境变量引导");
        }
        if (status == AdminStatus.DISABLED) {
            account.disable(clock.instant());
            auth.revokeAllFor(ActorType.ADMIN_ACCOUNT, accountId);
        } else {
            account.enable(clock.instant());
        }
        accounts.save(account);
    }

    /** 重置密码（改密后强制下线，旧会话立即失效）。 */
    public void resetPassword(Long accountId, String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        AdminAccount account = require(accountId);
        account.changePasswordHash(PasswordHasher.hash(rawPassword), clock.instant());
        accounts.save(account);
        auth.revokeAllFor(ActorType.ADMIN_ACCOUNT, accountId);
    }

    /** 设置操作员的能力清单（先清后设，幂等）。 */
    public void setPermissions(Long accountId, Set<PlatformPermission> permissions, Long bySuperAdmin) {
        AdminAccount account = require(accountId);
        if (account.isSuperAdmin()) {
            throw new IllegalArgumentException("超管天然全权，无需也不可改其能力清单");
        }
        for (PlatformPermission existing : grants.permissionsOf(ActorType.ADMIN_ACCOUNT, accountId)) {
            grants.revoke(ActorType.ADMIN_ACCOUNT, accountId, existing);
        }
        if (permissions != null) {
            for (PlatformPermission permission : permissions) {
                grants.grant(ActorType.ADMIN_ACCOUNT, accountId, permission, bySuperAdmin);
            }
        }
    }

    /** 强制下线（吊销该账号全部会话）。 */
    public int revokeSessions(Long accountId) {
        require(accountId);
        return auth.revokeAllFor(ActorType.ADMIN_ACCOUNT, accountId);
    }

    /**
     * 为账号生成并设置 TOTP 密钥，返回 {@code otpauth://} URL（供 authenticator app 扫码）。
     *
     * <p><b>只供「本人自助绑定」使用</b>：超管那条「替他人启用并取回密钥」的端点已移除——
     * 密钥一旦被第三方看到，第二因子对那个第三方即失效，而 TOTP 的意义之一正是防住
     * 「超管重置密码后直接登录」。
     *
     * <p>已启用时<b>拒绝</b>：避免静默换掉密钥（会让本人原有的 authenticator 失效且无人知晓）。
     */
    public String enableTotp(Long accountId) {
        AdminAccount account = require(accountId);
        if (account.hasTotp()) {
            throw new IllegalArgumentException("该账号已启用两步验证；如需重新绑定，请先解绑。");
        }
        String secret = TotpGenerator.newSecret();
        account.setTotpSecret(secret, clock.instant());
        accounts.save(account);
        return TotpGenerator.otpauthUrl("TGG 治理后台", account.getUsername(), secret);
    }

    /** 关闭账号的 TOTP 第二因子。 */
    public void disableTotp(Long accountId) {
        AdminAccount account = require(accountId);
        account.setTotpSecret(null, clock.instant());
        accounts.save(account);
    }

    /**
     * **本人解绑** TOTP：必须提供**当前有效**的验证码。
     *
     * <p>为什么要验证码：否则「拿到一个活动会话」就能关掉第二因子——等于第二因子白设。
     * （超管的 {@link #disableTotp} 保留为<b>恢复通道</b>：本人丢设备时的唯一出路，且该操作会被审计；
     * 其残余风险『超管可关闭他人 2FA』是刻意接受的运维代价，见 KNOWN-ISSUES。）
     */
    public void disableTotpWithCode(Long accountId, String code) {
        AdminAccount account = require(accountId);
        if (!account.hasTotp()) {
            throw new IllegalArgumentException("该账号未启用两步验证。");
        }
        if (!TotpGenerator.verify(account.getTotpSecret(), code, clock.instant())) {
            throw new IllegalArgumentException("验证码不正确。");
        }
        account.setTotpSecret(null, clock.instant());
        accounts.save(account);
    }

    private AdminAccount require(Long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("账号不存在：" + accountId));
    }
}
