package com.tg.heyisheng.bot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户可见的「命令 / 用法」文案<b>不得含模板符号</b>（{@code <...>}、{@code |}）。
 *
 * <p><b>它防的是什么（实测代价）</b>：运营者把用法整串照抄发了出去
 * （`/merchant_review <商家编号> approve|reject|need-more` 原样发出），命令因此一直回用法、
 * 看起来像坏了。文案本身要经得起照抄——给「可照抄的示例」，不给「参数模板」。
 *
 * <p><b>判据</b>：某个字符串字面量里**同时**出现「斜杠命令」（{@code /word}）与
 * {@code <} / {@code >} / {@code |} 即视为可疑。这样能放过两类合法用法：
 * 正则模式（{@code (?i)(a|b)}）与配置格式说明（{@code url|公钥Base64}、{@code <chatId>:<userId>}）
 * ——它们都不含斜杠命令。
 *
 * <p><b>为什么放在 tgg-app</b>：要跨模块扫源码，而 surefire 的工作目录是模块根；
 * 只有这里能同时看到各模块（与 {@code ConfigCatalogCompletenessTest} 同一取舍）。
 * 目录不存在时**显式失败**——守卫若在错误的工作目录下静默通过，就等于不存在。
 */
class UserFacingUsageTextTest {

    /** 参与扫描的模块（与 reactor 的业务模块一致）。 */
    private static final List<String> MODULES =
            List.of("tgg-core", "tgg-listing", "tgg-federation", "tgg-admin", "tgg-credit");

    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern SLASH_COMMAND = Pattern.compile("/[a-z][a-z_]{1,}");
    private static final Pattern TEMPLATE_MARK = Pattern.compile("[<>|]");
    /** 允许的标记：HTML 小标签（消息里偶尔用），不算模板符号。 */
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z]+>");

    @Test
    void userFacingCommandTextHasNoTemplateMarkers() throws IOException {
        Path root = Path.of("..");
        assertThat(root.resolve("tgg-core/src/main/java"))
                .as("测试工作目录应为 tgg-app 模块根（找不到 ../tgg-core）").exists();

        List<String> offenders = new ArrayList<>();
        for (String module : MODULES) {
            Path src = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(src)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    collectOffenders(file, offenders);
                }
            }
        }

        assertThat(offenders)
                .as("下列用户可见文案里用了 <...> / | 模板符号——照抄会失败，请改成可照抄的示例")
                .isEmpty();
    }

    private static void collectOffenders(Path file, List<String> offenders) throws IOException {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = LITERAL.matcher(lines.get(i));
            while (m.find()) {
                String text = HTML_TAG.matcher(m.group(1)).replaceAll("");
                if (SLASH_COMMAND.matcher(text).find() && TEMPLATE_MARK.matcher(text).find()) {
                    offenders.add(file + ":" + (i + 1) + " → " + m.group(1));
                }
            }
        }
    }
}
