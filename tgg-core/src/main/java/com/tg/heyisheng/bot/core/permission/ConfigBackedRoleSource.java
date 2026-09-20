package com.tg.heyisheng.bot.core.permission;

import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import jakarta.annotation.PostConstruct;

import java.util.List;

/**
 * 由配置中心**热驱动**的角色源（模块一 RBAC）。
 *
 * <p><b>它替换了什么</b>：原先 {@code TggCoreConfiguration} 在启动时把
 * {@code tgg.permission.admins} 解析进一个 {@link InMemoryRoleSource} 并就此固定——
 * 改授权必须重启。本类改为**调用期**解析，于是「后台改授权 → 立即生效」。
 *
 * <p><b>为什么仍保留启动期校验</b>：{@code tgg.permission.admins} 的格式非法时
 * {@link RoleGrantParser} 会抛 {@code TggConfigException}。若简单改成惰性解析，
 * 会把「启动就报错」推迟到「某条命令执行时才炸」——那是<b>回归</b>。
 * 故 {@code @PostConstruct} 仍校验一次当前值（fail-fast 保住），此后仅当值变化才重新解析。
 *
 * <p><b>解析缓存</b>：按「最近一次解析的 spec 字符串」缓存；spec 未变则复用，
 * 避免每条命令都重新解析。<b>不</b>做时间缓存——那会让变更生效延迟。
 */
public class ConfigBackedRoleSource implements RoleSource {

    /** 配置键（热读取）。 */
    public static final String KEY = "tgg.permission.admins";

    private final RuntimeConfigService config;

    private volatile String cachedSpec;
    private volatile InMemoryRoleSource cached;

    public ConfigBackedRoleSource(RuntimeConfigService config) {
        this.config = config;
    }

    /** 启动期校验一次当前值：格式非法即让应用起不来（与改造前的 fail-fast 一致）。 */
    @PostConstruct
    void validateInitialSpec() {
        current();
    }

    private InMemoryRoleSource current() {
        String spec = config.resolve(KEY).orElse("");
        InMemoryRoleSource local = cached;
        if (local != null && spec.equals(cachedSpec)) {
            return local;
        }
        InMemoryRoleSource fresh = new InMemoryRoleSource();
        RoleGrantParser.apply(fresh, spec);
        cached = fresh;
        cachedSpec = spec;
        return fresh;
    }

    @Override
    public Role roleOf(Long chatId, Long userId) {
        return current().roleOf(chatId, userId);
    }

    @Override
    public List<RoleGrant> grants() {
        return current().grants();
    }
}
