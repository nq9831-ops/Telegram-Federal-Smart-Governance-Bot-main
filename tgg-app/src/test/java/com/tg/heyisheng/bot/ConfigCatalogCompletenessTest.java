package com.tg.heyisheng.bot;

import com.tg.heyisheng.bot.core.config.dynamic.ConfigCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置中心的<b>完整性不变量</b>：{@code application.yml} 里出现的可配置键，必须都在
 * {@link ConfigCatalog} 里。
 *
 * <p><b>它防的是什么</b>：配置中心若只登记一部分键，会给出「配置都在这儿」的错觉——
 * 运维打开控制台看不到某个 cron 或阈值，会以为它不存在或不该配。这正是本项目最警惕的
 * 失败形态：**不是缺功能，是静默的不完整**。本轮审查实测到 12 个 yml 键没进目录
 * （5 个 cron + 6 个模块阈值 + 1 个驱动类名），本测试把它们钉住。
 *
 * <p><b>扫描边界（刻意的）</b>：只扫 {@code tgg.*} 与 {@code spring.datasource.*} 两类叶子键。
 * {@code springdoc.*} / {@code management.*} 属框架开关而非本项目的业务配置，不纳入。
 *
 * <p><b>为什么这个测试在 tgg-app</b>：{@code application.yml} 在 tgg-app 的 classpath 上，
 * 而 ConfigCatalog 在 tgg-core——只有 tgg-app 能同时看到两者。
 */
class ConfigCatalogCompletenessTest {

    /** 模块根目录（surefire 的工作目录即模块根）。 */
    private static final Path YML = Path.of("src/main/resources/application.yml");

    @Test
    void everyYmlConfigKeyIsRegisteredInTheCatalog() throws IOException {
        assertThat(YML).as("测试工作目录应为 tgg-app 模块根（找不到 application.yml）").exists();

        List<String> missing = leafKeys(YML).stream()
                .filter(key -> ConfigCatalog.find(key).isEmpty())
                .sorted()
                .toList();

        assertThat(missing)
                .as("以下键出现在 application.yml 里却没有登记进 ConfigCatalog——"
                        + "它们不会出现在配置中心，运维会以为不存在。请补进 ConfigCatalog"
                        + "（cron 类请标 restartRequired=true），或明确说明为何不纳入")
                .isEmpty();
    }

    /** 目录里每个键都要有说明——否则总览会显示空白的「说明」列。 */
    @Test
    void catalogKeysHaveNonBlankDescriptions() {
        ConfigCatalog.keys().forEach(key ->
                assertThat(key.description()).as("%s 缺说明", key.key()).isNotBlank());
    }

    /**
     * 从 yml 里抽取 {@code tgg.*} / {@code spring.datasource.*} 的<b>叶子键</b>（有值的那一行）。
     *
     * <p>用「缩进栈」还原完整路径：遇到有值行即为叶子（输出后不入栈），遇到纯命名空间行
     * （如 {@code tgg:}）则入栈供其子行使用。
     */
    private static Set<String> leafKeys(Path yml) throws IOException {
        List<String> lines = Files.readAllLines(yml);
        Deque<String> path = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        Set<String> keys = new LinkedHashSet<>();

        for (String raw : lines) {
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            // ⚠️ 冒号位置必须在**去缩进后**的内容上算——在原始行上算会得到错位索引，
            // 去缩进再按该索引 substring 就抛 StringIndexOutOfBounds（本测试第一版正是如此）。
            String content = line.stripLeading();
            int colon = content.indexOf(':');
            if (colon < 0) {
                continue;
            }
            int indent = line.length() - content.length();
            String name = content.substring(0, colon).strip();
            String value = content.substring(colon + 1).strip();
            if (name.isEmpty() || name.contains(" ") || name.contains("\t")) {
                continue;
            }

            while (!indents.isEmpty() && indents.peek() >= indent) {
                indents.pop();
                path.pop();
            }
            String full = path.isEmpty() ? name : String.join(".", reversed(path)) + "." + name;

            if (value.isEmpty()) {
                indents.push(indent);   // 命名空间行：供子行拼接
                path.push(name);
            } else if (full.startsWith("tgg.") || full.startsWith("spring.datasource.")) {
                keys.add(full);
            }
        }
        return keys;
    }

    /** 去注释：yml 里 {@code #} 只在行首或空白之后才是注释（避免截断 URL 里的 {@code #}）。 */
    private static String stripComment(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static List<String> reversed(Deque<String> stack) {
        return new ArrayList<>(stack).reversed();
    }
}
