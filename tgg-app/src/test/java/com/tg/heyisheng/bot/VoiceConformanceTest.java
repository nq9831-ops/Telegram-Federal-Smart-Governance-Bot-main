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


class VoiceConformanceTest {

    /** 参与扫描的模块（与 reactor 的业务模块一致）。 */
    private static final List<String> MODULES =
            List.of("tgg-core", "tgg-listing", "tgg-federation", "tgg-admin", "tgg-credit");

    /** 「已搬迁的包」——落点判据只在这些包内强制。新增搬迁域时在此登记。 */
    private static final List<String> MIGRATED_PACKAGES = List.of(
            "core/interaction", "core/notify", "core/dispatch",
            "core/admission", "core/breach", "core/moderation", "core/wordfilter",
            "admin/approval", "federation", "listing/command");

    /** 文案层类名后缀。 */
    private static final String MESSAGES_SUFFIX = "Messages.java";

    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern TEMPLATE_MARK = Pattern.compile("[<>|]");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d\\}|XXX|TODO|FIXME");
    /** 用户可见的调用点；同行出现中文字面量即视为「内联的用户可见文案」。 */
    private static final Pattern USER_VISIBLE_CALL = Pattern.compile(
            "\\.text\\(|answer\\(|reply\\(|sendMessage\\(|SendMessage\\(|return\\s");
    /**
     * 判据 3 只看**整句**：中文字符数达到该门槛才算「一句话」。
     *
     * <p><b>为什么按中文字符数而不是总长度</b>：拼接用的分隔符与单位（{@code "、"}、{@code " 条）：\n"}）
     * 在多个域里复用是正常的；而一整句（如 {@code "申诉已提交（编号 #"}）才是「改一处必然漏另一处」的对象。
     * 本门槛是实测校准出来的：联邦与审核两处都用 {@code " 条）：\n"} 作为列表标题后缀（合法复用），
     * 用总长度会误报。
     */
    private static final int DEDUP_MIN_CJK = 6;

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
                if (isComment(line) || mentionsLog(line) || !USER_VISIBLE_CALL.matcher(line).find()) {
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
                    if (cjkCount(text) >= DEDUP_MIN_CJK) {
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

    /**
     * 日志行不是用户文案——{@code NotificationSender.logging()} 用
     * {@code return (id, text) -> log.info("通知（未配置投递通道…）")} 这种形式返回 lambda，
     * 行内确实有中文字面量，但用户看不到它。不加这条排除，放宽到 {@code return} 会立刻误报。
     */
    private static boolean mentionsLog(String line) {
        return line.contains("log.");
    }

    /** 字面量里的中文字符个数（判据 3 的门槛按它算，见 {@link #DEDUP_MIN_CJK}）。 */
    private static int cjkCount(String text) {
        Matcher m = CJK.matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }
}
