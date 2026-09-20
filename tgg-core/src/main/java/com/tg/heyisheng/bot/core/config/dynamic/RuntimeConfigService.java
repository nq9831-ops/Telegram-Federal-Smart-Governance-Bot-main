package com.tg.heyisheng.bot.core.config.dynamic;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 运行期配置服务——配置中心的核心。
 *
 * <p><b>解析顺序</b>：{@code config_override}（本表） → {@link Environment}（环境变量 / {@code application.yml} 默认）。
 * 覆盖值启动时加载一次进内存缓存；写入时就地更新缓存，读端点与**热参数消费方**据此立即看到新值。
 *
 * <p><b>为什么用内存缓存而不是每次查库</b>：热参数可能在每次消息处理时被读取（如审批统计），
 * 逐次查库不必要。写入路径会同步刷新缓存，且本服务是配置的**唯一写入点**，
 * 故缓存与库不会漂移。多副本部署下每个副本在启动时读同一张表，覆盖值一致——
 * 唯一代价是「A 副本改配置、B 副本要等重启才看到」——这正是「需重启生效」的诚实语义，
 * 而非缺陷（见 {@link ConfigKey#restartRequired()}）。
 *
 * <p><b>校验在服务端</b>：类型、上下界、装配开关的前置条件都在此拦截。前端校验只是体验，
 * 挡不住直接调接口；而一条非法值落库后会在**下次启动** fail-fast 把应用砖掉。
 */
@Service
public class RuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigService.class);

    private final ConfigOverrideRepository overrides;
    private final Environment environment;
    private final Clock clock;

    /** 键 → 覆盖值（仅覆盖，不含环境/默认；缺键即「无覆盖」）。 */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 覆盖缓存 TTL 的配置键。
     *
     * <p><b>刻意只从 {@link Environment} 读，不经本服务的解析链</b>——否则会形成
     * 「读配置以决定如何读配置」的递归。故它在配置中心里是只读项。
     */
    static final String CACHE_TTL_KEY = "tgg.admin.config-cache-ttl-seconds";
    private static final long DEFAULT_TTL_SECONDS = 10;

    /** 上次成功/尝试加载覆盖的时间（毫秒）。 */
    private volatile long loadedAtMillis;
    /** 防止 TTL 到期时多线程同时刷新（抢不到锁的线程沿用旧值，最终一致）。 */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    @Autowired
    public RuntimeConfigService(ConfigOverrideRepository overrides, Environment environment) {
        this(overrides, environment, Clock.systemUTC());
    }

    /** 可注入时钟的构造器（测试用；也让本服务不绑定系统时钟）。 */
    public RuntimeConfigService(ConfigOverrideRepository overrides, Environment environment, Clock clock) {
        this.overrides = overrides;
        this.environment = environment;
        this.clock = clock;
        // 立即视为「刚加载过」——否则一个还没加载过缓存的实例（如单测里手工 new 的）
        // 会在第一次 resolve 时被判为过期，进而在无数据的仓库替身上炸出 NPE。
        this.loadedAtMillis = clock.millis();
    }

    /** 启动加载覆盖值。失败不阻断启动——配置中心是增强能力，不该成为启动的单点。 */
    @PostConstruct
    void initialLoad() {
        try {
            reload();
        } catch (RuntimeException ex) {
            log.warn("配置覆盖加载失败（配置中心降级为「仅环境变量/默认」，不影响运行）：{}",
                    ex.getClass().getSimpleName(), ex);
        }
    }

    /** 从库重载覆盖值到缓存。 */
    @Transactional(readOnly = true)
    public void reload() {
        try {
            Map<String, String> fresh = new ConcurrentHashMap<>();
            for (ConfigOverride override : overrides.findAll()) {
                fresh.put(override.getConfigKey(), override.getConfigValue());
            }
            cache.clear();
            cache.putAll(fresh);
            log.info("配置覆盖已加载：{} 条", fresh.size());
        } finally {
            // 失败也记时间戳：把重试节流到 TTL 一次，避免每次 resolve 都撞同一个错误刷日志。
            loadedAtMillis = clock.millis();
        }
    }

    /**
     * TTL 到期就从库重读覆盖值——**这是多副本下「热参数真的热」的前提**。
     *
     * <p>缓存是进程内的：A 副本经 Web 改了配置，B 副本的内存缓存不会自动知道。
     * 有 TTL 后，B 最多在 TTL 内看到新值（默认 10 秒）。单副本部署下这只是一次廉价空转。
     */
    private void refreshIfStale() {
        long ttlSeconds = ttlSeconds();
        if (ttlSeconds <= 0) {
            return;   // 显式关闭 TTL：退化为「只启动加载 + 本地写入即更新」
        }
        if (clock.millis() - loadedAtMillis < ttlSeconds * 1000L) {
            return;
        }
        if (!refreshing.compareAndSet(false, true)) {
            return;   // 已有线程在刷新，本次沿用旧值
        }
        try {
            reload();
        } catch (RuntimeException ex) {
            log.warn("配置覆盖刷新失败，本次沿用旧缓存：{}", ex.getMessage());
        } finally {
            refreshing.set(false);
        }
    }

    private long ttlSeconds() {
        String raw = environment.getProperty(CACHE_TTL_KEY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TTL_SECONDS;
        }
        try {
            return Math.max(0, Long.parseLong(raw.trim()));
        } catch (NumberFormatException ex) {
            log.warn("{} 的值「{}」不是整数，按默认 {} 秒处理", CACHE_TTL_KEY, raw, DEFAULT_TTL_SECONDS);
            return DEFAULT_TTL_SECONDS;
        }
    }

    /** 该键当前是否有覆盖值。 */
    public boolean hasOverride(String key) {
        refreshIfStale();
        return cache.containsKey(key);
    }

    /**
     * 解析键的**生效值**：覆盖 → 环境/默认。
     *
     * @return 生效值；三者皆无时为空
     */
    public Optional<String> resolve(String key) {
        refreshIfStale();
        String override = cache.get(key);
        if (override != null) {
            return Optional.of(override);
        }
        String fromEnvironment = environment.getProperty(key);
        if (fromEnvironment != null) {
            return Optional.of(fromEnvironment);
        }
        return ConfigCatalog.find(key).map(ConfigKey::defaultValue);
    }

    /** 生效值来源，供总览展示：{@code override} / {@code environment} / {@code default} / {@code unset}。 */
    public String sourceOf(String key) {
        refreshIfStale();
        if (cache.containsKey(key)) {
            return "override";
        }
        if (environment.getProperty(key) != null) {
            return "environment";
        }
        return ConfigCatalog.find(key).map(ConfigKey::defaultValue).orElse(null) != null ? "default" : "unset";
    }

    // ─────────────────────── 类型化读取（热参数消费方用）───────────────────────

    public int getInt(String key, int fallback) {
        String raw = resolve(key).orElse(null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            log.warn("配置 {} 的值「{}」不是整数，回落到默认 {}（请检查配置中心/环境变量）", key, raw, fallback);
            return fallback;
        }
    }

    public long getLong(String key, long fallback) {
        String raw = resolve(key).orElse(null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            log.warn("配置 {} 的值「{}」不是整数，回落到默认 {}", key, raw, fallback);
            return fallback;
        }
    }

    public boolean getBoolean(String key, boolean fallback) {
        String raw = resolve(key).orElse(null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    /** 小时数配置 → {@link Duration}。 */
    public Duration getHours(String key, long fallbackHours) {
        return Duration.ofHours(getLong(key, fallbackHours));
    }

    /** 逗号分隔的 userId 白名单；非数字项忽略（与既有 Guard 的容错口径一致）。 */
    public Set<Long> getCsvIds(String key) {
        String raw = resolve(key).orElse(null);
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(token));
            } catch (NumberFormatException ex) {
                log.warn("配置 {} 含非数字项，已忽略：{}", key, token);
            }
        }
        return Set.copyOf(ids);
    }

    // ─────────────────────────────── 写入 ───────────────────────────────

    /**
     * 写入一条覆盖。
     *
     * @param operator 操作者 userId（已由上层验明写权限）；可为 null（系统/测试）
     * @return 落库后的值
     * @throws IllegalArgumentException 未知键 / 不可写 / 值非法 / 前置条件不满足
     */
    @Transactional
    public String set(String key, String rawValue, Long operator) {
        ConfigKey meta = ConfigCatalog.find(key)
                .orElseThrow(() -> new ConfigWriteException(ConfigWriteException.Kind.UNKNOWN_KEY,
                        "未知配置键：" + key));
        if (!meta.writable()) {
            throw new ConfigWriteException(ConfigWriteException.Kind.NOT_WRITABLE,
                    "该配置不可经 Web 改写（分类 " + meta.category() + "）：" + key);
        }
        String value = rawValue == null ? "" : rawValue.trim();
        // 值级失败统一归为 INVALID_VALUE——调用方据此回 400（改改就能过），与「键不存在」「不可写」区分开。
        try {
            validateValue(meta, value);
            validatePrerequisite(meta, value);
        } catch (ConfigWriteException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new ConfigWriteException(ConfigWriteException.Kind.INVALID_VALUE, ex.getMessage());
        }

        overrides.save(new ConfigOverride(key, value, clock.instant(), operator));
        cache.put(key, value);
        log.info("配置已覆盖：key={} value={} operator={}", key, display(meta, value), operator);
        return value;
    }

    /** 删除覆盖，回落到环境变量/默认。 */
    @Transactional
    public void clear(String key, Long operator) {
        ConfigCatalog.find(key).orElseThrow(() -> new ConfigWriteException(
                ConfigWriteException.Kind.UNKNOWN_KEY, "未知配置键：" + key));
        overrides.deleteById(key);
        cache.remove(key);
        log.info("配置覆盖已清除：key={} operator={}", key, operator);
    }

    /** 全量快照（只读总览用）；密钥类只回显「已设 / 未设」，**绝不回显明文**。 */
    public List<Resolved> snapshot() {
        List<Resolved> views = new ArrayList<>();
        for (ConfigKey meta : ConfigCatalog.keys()) {
            String effective = resolve(meta.key()).orElse(null);
            views.add(new Resolved(
                    meta.key(), meta.category(), meta.type(), meta.secret(), meta.writable(),
                    meta.restartRequired(), display(meta, effective), meta.defaultValue(),
                    sourceOf(meta.key()), meta.description()));
        }
        return views;
    }

    /** 密钥键只回显打码值；其余原样（null/空白统一为「未设置」）。 */
    private static String display(ConfigKey meta, String value) {
        boolean blank = value == null || value.isBlank();
        if (meta.secret()) {
            return blank ? "未设置" : "***";
        }
        return blank ? "未设置" : value;
    }

    // ─────────────────────────────── 校验 ───────────────────────────────

    private static void validateValue(ConfigKey meta, String value) {
        switch (meta.type()) {
            case BOOLEAN -> {
                if (!value.equals("true") && !value.equals("false")) {
                    throw new IllegalArgumentException(
                            "布尔配置只能取 true / false，收到：" + value);
                }
            }
            case INT, LONG, HOURS -> {
                long parsed;
                try {
                    parsed = Long.parseLong(value);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("该配置需为整数，收到：" + value);
                }
                if (meta.min() != null && parsed < meta.min()) {
                    throw new IllegalArgumentException("低于允许下界 " + meta.min() + "：" + value);
                }
                if (meta.max() != null && parsed > meta.max()) {
                    throw new IllegalArgumentException("超过允许上界 " + meta.max() + "：" + value);
                }
                if (meta.type() == ConfigValueType.HOURS && parsed < 1) {
                    throw new IllegalArgumentException("小时数必须 ≥ 1，收到：" + value);
                }
            }
            case CSV_IDS -> {
                for (String part : value.split(",")) {
                    String token = part.trim();
                    if (token.isEmpty()) {
                        continue;
                    }
                    try {
                        Long.parseLong(token);
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException(
                                "白名单须为逗号分隔的 userId（数字），非法项：" + token);
                    }
                }
            }
            case STRING -> {
                if (value.length() > 1024) {
                    throw new IllegalArgumentException("值超长（上限 1024 字符）");
                }
            }
            case ROLE_GRANTS -> {
                // 热化后 RoleGrantParser 的解析移到了调用期；格式错误必须在此拦下，
                // 否则一条笔误会在下次启动或下次命令执行时才炸（丢失 fail-fast）。
                try {
                    com.tg.heyisheng.bot.core.permission.RoleGrantParser.apply(
                            new com.tg.heyisheng.bot.core.permission.InMemoryRoleSource(), value);
                } catch (RuntimeException ex) {
                    throw new IllegalArgumentException("授权串格式非法：" + ex.getMessage());
                }
            }
            case CRON -> {
                // 非法 cron 会让 Spring 在**下次启动**解析 @Scheduled 时失败——而改 cron 的典型场景
                // 正是「改完重启生效」，不拦就等于给了个「一重启就起不来」的按钮。
                if (!org.springframework.scheduling.support.CronExpression.isValidExpression(value)) {
                    throw new IllegalArgumentException("cron 表达式非法：" + value);
                }
            }
        }
    }

    /**
     * 装配开关的前置条件：置为 {@code true} 时，其依赖键必须已设。
     *
     * <p><b>为什么必须拦</b>：这些依赖在启动期 fail-fast（缺私钥/缺 key/缺节点即启动失败）。
     * 若允许后台在无前置时打开开关，一次误点就会让**下次启动直接起不来**。
     */
    private void validatePrerequisite(ConfigKey meta, String value) {
        if (!"true".equals(value) || meta.requiresKeyWhenEnabled() == null) {
            return;
        }
        String required = meta.requiresKeyWhenEnabled();
        String actual = resolve(required).orElse(null);
        if (actual == null || actual.isBlank()) {
            throw new IllegalArgumentException(
                    "启用 " + meta.key() + " 需先配置 " + required + "（否则下次启动会 fail-fast）");
        }
    }

    /** 总览项。 */
    public record Resolved(String key, ConfigCategory category, ConfigValueType type,
                           boolean secret, boolean editable, boolean restartRequired,
                           String effectiveValue, String defaultValue, String source,
                           String description) {
    }
}
