package com.tg.heyisheng.bot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语气规范（{@code docs/VOICE.md}）**可机器判定**部分的守门测试。三条判据：
 *
 * <ol>
 *   <li><b>文案层自身干净</b>：{@code *Messages} 类里的字面量不得含模板符号（{@code < > |}）
 *       或未填占位符（{@code {0}}/XXX/TODO/FIXME）——照抄会失败、或渲染出看不懂的东西。</li>
 *   <li><b>落点</b>：**已搬迁的包**内，用户可见调用点同行不得再出现中文字面量——
 *       文案必须来自 {@code *Messages} 常量，否则第三步「一处改」会重新漏气。</li>
 *   <li><b>不得重新散落</b>：同一句（含中文且长度 ≥5 的字面量）不得在两个 {@code *Messages} 类里各写一份
 *       ——这正是本步要消除的 {@code UNKNOWN = "未知操作。"} 那种重复。</li>
 * </ol>
 *
 * <p><b>为什么放在 {@code tgg-app}</b>：要跨模块扫源码，而 surefire 的工作目录是模块根；
 * 只有这里能同时看到各模块（与 {@code UserFacingUsageTextTest}、{@code ConfigCatalogCompletenessTest}
 * 同一取舍）。目录不存在时**显式失败**——守卫若在错误的工作目录下静默通过，就等于不存在。
 *
 * <p><b>范围是有意收窄的</b>：第二步按收口只搬「交互 + 通知 + 自助」三层，
 * 故判据 2 只覆盖 {@link #MIGRATED_PACKAGES}；惩戒类（moderation/wordfilter）与业务域
 * 留到第三步随语气一起搬，届时把这些包加进来即可（判据本身不用改）。
 *
 * <p><b>判据 2 的已知边界（如实记录）</b>：它只匹配**调用点**行（{@code .text(} / {@code answer(} /
 * {@code reply(} / {@code sendMessage(} / {@code SendMessage(}），因此**组合句**（如视图中
 * {@code "「" + title + "」—— …"}）与 {@code return} 直接返回的字面量不在覆盖内——
 * 那类句子由视图/格式化持有（见 {@code docs/VOICE.md} 的组织规则），靠评审而非扫描保证。
 */
class VoiceConformanceTest {

    /** 参与扫描的模块（与 reactor 的业务模块一致）。 */
    private static final List<String> MODULES =
            List.of("tgg-core", "tgg-listing", "tgg-federation", "tgg-admin", "tgg-credit");

    /** 「已搬迁的包」——落点判据只在这些包内强制。新增搬迁域时在此登记。 */
    private static final List<String> MIGRATED_PACKAGES = List.of(
            "core/interaction", "core/notify", "core/dispatch");

    /** 文案层类名后缀。 */
    private static final String MESSAGES_SUFFIX = "Messages.java";

    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern TEMPLATE_MARK = Pattern.compile("[<>|]");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d\\}|XXX|TODO|FIXME");
    /** 用户可见的调用点；同行出现中文字面量即视为「内联的用户可见文案」。 */
    private static final Pattern USER_VISIBLE_CALL = Pattern.compile(
            "\\.text\\(|answer\\(|reply\\(|sendMessage\\(|SendMessage\\(");
    /** 判据 3 的字面量长度门槛（太短的串撞车属正常，不视为重复散落）。 */
    private static final int DEDUP_MIN_LENGTH = 5;

    private static Path moduleRoot() {
        Path root = Path.of("..");
        assertThat(root.resolve("tgg-core/src/main/java"))
                .as("测试工作目录应为 tgg-app 模块根（找不到 ../tgg-core）").exists();
        return root;
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String module : MODULES) {
            Path src = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(src)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            }
        }
        return files;
    }

    /** 判据 1：文案层自身不得含模板符号或未填占位符。 */
    @Test
    void messagesClassesCarryNoTemplateMarkersOrPlaceholders() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(moduleRoot())) {
            if (!file.getFileName().toString().endsWith(MESSAGES_SUFFIX)) {
                continue;
            }
            for (String line : Files.readAllLines(file)) {
                if (isComment(line)) {
                    continue;
                }
                Matcher m = LITERAL.matcher(line);
                while (m.find()) {
                    String text = m.group(1);
                    if (TEMPLATE_MARK.matcher(text).find() || PLACEHOLDER.matcher(text).find()) {
                        offenders.add(file + " → " + text);
                    }
                }
            }
        }
        assertThat(offenders)
                .as("文案层里的模板符号会让运营者照抄失败；未填占位符会渲染出看不懂的东西")
                .isEmpty();
    }

    /** 判据 2：已搬迁的包内，用户可见调用点不得再内联中文字面量。 */
    @Test
    void migratedPackagesHoldNoInlineUserVisibleCopy() throws IOException {
        Path root = moduleRoot();
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            if (!inMigratedPackage(root, file) || file.getFileName().toString().endsWith(MESSAGES_SUFFIX)) {
                continue;
            }
            int lineNo = 0;
            for (String line : Files.readAllLines(file)) {
                lineNo++;
                if (isComment(line) || !USER_VISIBLE_CALL.matcher(line).find()) {
                    continue;
                }
                Matcher m = LITERAL.matcher(line);
                while (m.find()) {
                    if (CJK.matcher(m.group(1)).find()) {
                        offenders.add(file + ":" + lineNo + " → " + m.group(1));
                    }
                }
            }
        }
        assertThat(offenders)
                .as("已搬迁的包内不得再内联用户可见文案——请引用对应的 *Messages 常量")
                .isEmpty();
    }

    /** 判据 3：同一句不得在两个 *Messages 类里各写一份（本步要消除的重复）。 */
    @Test
    void copyIsNotDuplicatedAcrossMessagesClasses() throws IOException {
        Map<String, Set<String>> owners = new HashMap<>();
        for (Path file : javaFiles(moduleRoot())) {
            if (!file.getFileName().toString().endsWith(MESSAGES_SUFFIX)) {
                continue;
            }
            for (String line : Files.readAllLines(file)) {
                if (isComment(line)) {
                    continue;
                }
                Matcher m = LITERAL.matcher(line);
                while (m.find()) {
                    String text = m.group(1);
                    if (text.length() >= DEDUP_MIN_LENGTH && CJK.matcher(text).find()) {
                        owners.computeIfAbsent(text, k -> new LinkedHashSet<>())
                                .add(file.getFileName().toString());
                    }
                }
            }
        }
        List<String> duplicated = owners.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " → " + e.getValue())
                .toList();
        assertThat(duplicated)
                .as("同一句在两个文案类里各写一份，改一处必然漏另一处")
                .isEmpty();
    }

    private static boolean inMigratedPackage(Path root, Path file) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        return MIGRATED_PACKAGES.stream().anyMatch(relative::contains);
    }

    /**
     * 注释行里的引号不是文案——javadoc 常写 {@code UNKNOWN = "未知操作。"} 这类示例，
     * 不排除会把说明文字当成待搬迁的文案（本测试首版正因此误报）。
     */
    private static boolean isComment(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*");
    }
}
