package com.tg.heyisheng.bot.core.config.dynamic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置键目录的<b>不变量</b>测试。
 *
 * <p>它守的是「目录本身自洽」——键唯一、分类与可写性一致、装配开关都标了重启、
 * 每条都有说明与合理上下界。这些错了不会抛异常，只会让总览显示错、写入放行不该放的键、
 * 或前端拿到误导性描述——典型的静默失效，故用测试钉住。
 */
class ConfigCatalogTest {

    @Test
    void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (ConfigKey key : ConfigCatalog.keys()) {
            assertThat(seen.add(key.key())).as("配置键重复：%s", key.key()).isTrue();
        }
    }

    /** 密钥 / 引导态**永不**可写——这是「统一管理」里最不能妥协的一条。 */
    @Test
    void secretAndBootstrapKeysAreNeverWritable() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            if (key.category() == ConfigCategory.SECRET || key.category() == ConfigCategory.BOOTSTRAP) {
                assertThat(key.writable())
                        .as("%s 属 %s，绝不可经 Web 改写", key.key(), key.category())
                        .isFalse();
            }
        }
    }

    /** 密钥键必须标 secret（否则总览会回显明文）；非密钥键反之应能正常展示。 */
    @Test
    void everyCategorySecretKeyIsMarkedSecret() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            if (key.category() == ConfigCategory.SECRET) {
                assertThat(key.secret()).as("%s 是密钥，必须标记 secret", key.key()).isTrue();
            }
        }
    }

    /** 装配开关（@ConditionalOnProperty）在启动期求值——必须诚实标注「重启生效」。 */
    @Test
    void assemblySwitchesRequireRestart() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            if (key.category() == ConfigCategory.ASSEMBLY) {
                assertThat(key.restartRequired())
                        .as("%s 是装配开关，必须标注重启生效", key.key())
                        .isTrue();
            }
        }
    }

    @Test
    void everyKeyHasDescription() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            assertThat(key.description()).as("%s 缺说明（总览会显示空白）", key.key()).isNotBlank();
        }
    }

    /** 前置条件键必须真实存在于目录中，否则校验会「查了个不存在的键」。 */
    @Test
    void prerequisiteReferencesExistingKey() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            if (key.requiresKeyWhenEnabled() != null) {
                assertThat(ConfigCatalog.find(key.requiresKeyWhenEnabled()))
                        .as("%s 的前置键 %s 不在目录中", key.key(), key.requiresKeyWhenEnabled())
                        .isPresent();
            }
        }
    }

    @Test
    void boundsAreSane() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            if (key.min() != null && key.max() != null) {
                assertThat(key.min()).as("%s 的 min 不应大于 max", key.key())
                        .isLessThanOrEqualTo(key.max());
            }
        }
    }

    /** 首批热参数：可写且**不**需重启（其消费方已改造为调用期读取）。 */
    @Test
    void hotAdminOverdueParamsAreWritableAndHot() {
        for (String hot : new String[]{
                "tgg.admin.overdue-remind-hours", "tgg.admin.overdue-escalate-hours"}) {
            ConfigKey key = ConfigCatalog.find(hot).orElseThrow();
            assertThat(key.writable()).as("%s 应可写", hot).isTrue();
            assertThat(key.restartRequired()).as("%s 应标注为热生效", hot).isFalse();
        }
    }

    /**
     * 不变量：可写的**运行期参数**必须显式声明它是「热生效」还是「需重启」。
     *
     * <p>本测试的前一版要求「全部必须热」——被新增的 cron 键证伪：cron 可写，但
     * {@code @Scheduled(cron = "${...}")} 在装配期就把表达式固定了，改了确实要重启。
     * 所以真正该守的不是「都热」，而是**不许含糊**：说明里必须写明其中一种，
     * 否则运维改了配置却无从判断要不要重启。
     */
    @Test
    void everyWritableRuntimeKeyDeclaresHotOrRestart() {
        for (ConfigKey key : ConfigCatalog.keys()) {
            if (key.category() == ConfigCategory.RUNTIME && key.writable()) {
                assertThat(key.description())
                        .as("%s 可写却是运行期参数，说明里必须写明「热生效」或「需重启」", key.key())
                        .matches("(?s).*(热生效|需重启).*");
            }
        }
    }

    /** 保留开关不门控装配（RetentionConfiguration 的 bean 无条件创建），故属运行期参数而非装配开关。 */
    @Test
    void retentionEnabledIsRuntimeNotAssembly() {
        assertThat(ConfigCatalog.find("tgg.retention.enabled").orElseThrow().category())
                .isEqualTo(ConfigCategory.RUNTIME);
    }

    // ───────────────────────── 静态护栏：热声明的诚实性 ─────────────────────────

    /** 键字面量（tgg.* / spring.datasource.*）。 */
    private static final Pattern KEY_LITERAL =
            Pattern.compile("\"((?:tgg|spring\\.datasource)\\.[A-Za-z0-9_.-]+)\"");
    /** 常量声明与其字面量值（{@code String KEY = "tgg.x"} 的各种形态）。 */
    private static final Pattern CONSTANT =
            Pattern.compile("(?:static\\s+final\\s+String|final\\s+String|String)\\s+(\\w+)\\s*=\\s*\"([^\"]+)\"");

    /**
     * 静态护栏：**可写且标热生效**的键，必须在源码里**经调用期读取**（防声明漂移）。
     *
     * <p><b>它防的是什么</b>：{@link ConfigKey#restartRequired()} 为 {@code false} 是一句承诺
     * （「改了配置不用重启」）。若消费方其实在启动期把值固化了（{@code @ConfigurationProperties}
     * 绑定、构造期捕获等），这句承诺就是假的——运维在配置中心改完以为即时生效，实际要重启才生效。
     * 这类漂移编译期无感、运行期无害（直到出事），只有静态扫描能钉住。
     *
     * <p><b>扫描口径（保守、只多不少）</b>：键字面量出现在任一模块 {@code src/main/java} 的源文件里，
     * 且被当作某个方法调用的实参传入（即「有人真的在调用期拿它去读」）。声明处
     * {@link ConfigCatalog} 自身排除在外——它只是登记，不是消费。判定为「是」即放行，允许夹具
     * （如 {@code days(key, fallback)} 这类薄封装）透传。
     */
    @Test
    void writableHotKeysAreReadAtCallTime() throws IOException {
        Set<String> hot = ConfigCatalog.keys().stream()
                .filter(ConfigKey::writable)
                .filter(key -> !key.restartRequired())
                .map(ConfigKey::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(hot).as("应存在可写且热生效的键（否则本护栏是空转）").isNotEmpty();

        Set<String> consumedAtCallTime = keysReadAtCallTime(repositoryRoot());
        List<String> drifted = hot.stream()
                .filter(key -> !consumedAtCallTime.contains(key))
                .sorted()
                .toList();

        assertThat(drifted)
                .as("以下键在 ConfigCatalog 标了「热生效」（restartRequired=false）却在源码里找不到"
                        + "任何调用期读取——声明漂移。要么把消费方改为调用期读 RuntimeConfigService，"
                        + "要么在 ConfigCatalog 诚实地改为 restartRequired=true + 「改动需重启」")
                .isEmpty();
    }

    /** 仓库根：测试工作目录是 tgg-core 模块根。 */
    private static Path repositoryRoot() {
        Path root = Path.of("..");
        assertThat(root.resolve("tgg-core/src/main/java"))
                .as("测试工作目录应为 tgg-core 模块根（找不到 ../tgg-core/src/main/java）").exists();
        return root;
    }

    /** 扫描所有 {@code tgg-*&#47;src/main/java} 源文件，收集「被当作调用实参传入」的配置键。 */
    private static Set<String> keysReadAtCallTime(Path repositoryRoot) throws IOException {
        Set<String> read = new LinkedHashSet<>();
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(repositoryRoot, "tgg-*")) {
            for (Path module : modules) {
                Path mainJava = module.resolve("src/main/java");
                if (!Files.isDirectory(mainJava)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(mainJava)) {
                    for (Path file : files.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".java"))
                            // 声明处只是登记，不是消费——排除，否则每个键都会「自己引用自己」
                            .filter(path -> !path.getFileName().toString().equals("ConfigCatalog.java"))
                            .toList()) {
                        collectReadKeys(Files.readString(file), read);
                    }
                }
            }
        }
        return read;
    }

    /** 从一个源文件收集：直接字面量、或绑定该字面量的常量，只要它被当作某个调用的实参。 */
    private static void collectReadKeys(String source, Set<String> read) {
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher constant = CONSTANT.matcher(source);
        while (constant.find()) {
            constants.put(constant.group(1), constant.group(2));
        }
        for (Map.Entry<String, String> entry : constants.entrySet()) {
            if (passedAsCallArgument(source, entry.getKey())) {
                read.add(entry.getValue());
            }
        }
        Matcher literal = KEY_LITERAL.matcher(source);
        while (literal.find()) {
            if (passedAsCallArgument(source, "\"" + literal.group(1) + "\"")) {
                read.add(literal.group(1));
            }
        }
    }

    /** 该 token（带引号的字面量，或标识符）是否被当作某个方法调用的实参传入——即「调用期读取」。 */
    private static boolean passedAsCallArgument(String source, String token) {
        return Pattern.compile("\\w+\\s*\\(\\s*" + Pattern.quote(token) + "(?!\\w)")
                .matcher(source)
                .find();
    }
}
