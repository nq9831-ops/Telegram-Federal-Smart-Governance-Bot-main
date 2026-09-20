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

    @Autowired
    public RuntimeConfigService(ConfigOverrideRepository overrides, Environment environment) {
        this(overrides, environment, Clock.systemUTC());
    }

    /** 可注入时钟的构造器（测试用；也让本服务不绑定系统时钟）。 */
    public RuntimeConfigService(ConfigOverrideRepository overrides, Environment environment, Clock clock) {
        this.overrides = overrides;
        this.environment = environment;
        this.clock = clock;
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
        Map<String, String> fresh = new ConcurrentHashMap<>();
        for (ConfigOverride override : overrides.findAll()) {
            fresh.put(override.getConfigKey(), override.getConfigValue());
        }
        cache.clear();
        cache.putAll(fresh);
        log.info("配置覆盖已加载：{} 条", fresh.size());
    }

    /** 该键当前是否有覆盖值。 */
    public boolean hasOverride(String key) {
        return cache.containsKey(key);
    }

    /**
     * 解析键的**生效值**：覆盖 → 环境/默认。
     *
     * @return 生效值；三者皆无时为空
     */
    public Optional<String> resolve(String key) {
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
