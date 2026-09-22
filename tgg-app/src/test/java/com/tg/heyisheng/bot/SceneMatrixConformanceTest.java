package com.tg.heyisheng.bot;

import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「场景 × 权限」矩阵（{@code USER-GUIDE.md}）与真实命令注册表的<b>同源防漂移</b>守卫。
 *
 * <p><b>它防的是什么</b>：文档矩阵是第二份「命令 → 场景/权限」的表——手写表必然随功能增长
 * 静默失真（本仓 USER-GUIDE 的命令计数就漂过 29→34）。用户拍板的约束是「判据必须与
 * {@code MenuCatalog} 同源，别再养一份会漂移的表」，本测试是那句话的机械形态：
 * 增删命令而没更新矩阵 → 变红；群限定集合与 {@code @BotCommand(groupOnly)} 声明不一致 → 变红。
 *
 * <p>两条判据（只认 {@code USER-GUIDE.md} 标记块 {@code <!-- 场景矩阵 -->} …
 * {@code <!-- /场景矩阵 -->} 之间的内容）：
 * <ol>
 *   <li><b>覆盖</b>：注册表里的每个主命令都必须以 {@code /命令名} 形态出现在矩阵内
 *       （矩阵允许多写——联邦命令在默认测试配置下不在注册表里，见下）；</li>
 *   <li><b>群限定行同源</b>：矩阵里「只能在群里用」所在行的命令集合，必须<b>恰好等于</b>
 *       {@code @BotCommand(groupOnly = true)} 的声明集合（与执行硬门的同步由
 *       {@code CommandMenuContentTest} 钉住，这里钉文档侧）。</li>
 * </ol>
 *
 * <p><b>覆盖面说明</b>（与 {@code CommandMenuContentTest} 同一取舍）：测试环境开启
 * listing + merchant、联邦默认关闭——联邦四条（/appeal·/pending·/approve·/reject）不在注册表，
 * 由矩阵多写覆盖；判据 1 是「注册表 ⊆ 矩阵」方向，天然兼容。
 */
@SpringBootTest(properties = {
        "tgg.listing.enabled=true",
        "tgg.merchant.enabled=true"
})
class SceneMatrixConformanceTest {

    private static final String BLOCK_BEGIN = "<!-- 场景矩阵 -->";
    private static final String BLOCK_END = "<!-- /场景矩阵 -->";
    /** 「只能在群里用」的标注短语——群限定行的判据（逐字对齐矩阵里的表述）。 */
    private static final String GROUP_ONLY_MARK = "只能在群里用";
    private static final Pattern COMMAND = Pattern.compile("/([a-z][a-z_]*)");

    @Autowired
    private CommandRegistry registry;

    private static Path userGuide() {
        Path root = Path.of("..");
        assertThat(root.resolve("tgg-core/src/main/java"))
                .as("测试工作目录应为 tgg-app 模块根（找不到 ../tgg-core）").exists();
        Path guide = root.resolve("USER-GUIDE.md");
        assertThat(guide).as("USER-GUIDE.md 缺失——矩阵的唯一供养处没了，显式失败").exists();
        return guide;
    }

    private static List<String> matrixLines() throws IOException {
        String text = Files.readString(userGuide());
        int begin = text.indexOf(BLOCK_BEGIN);
        int end = text.indexOf(BLOCK_END);
        assertThat(begin).as("USER-GUIDE.md 缺少矩阵起始标记 %s", BLOCK_BEGIN).isNotNegative();
        assertThat(end).as("USER-GUIDE.md 缺少矩阵结束标记 %s", BLOCK_END).isGreaterThan(begin);
        return text.substring(begin, end).lines().toList();
    }

    private static Set<String> commandsIn(String line) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = COMMAND.matcher(line);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    /** 判据 1：真实命令必须在矩阵里出现（矩阵多写兼容，漏写变红）。 */
    @Test
    void everyRegisteredCommandAppearsInTheMatrix() throws IOException {
        Set<String> inMatrix = matrixLines().stream()
                .flatMap(line -> commandsIn(line).stream())
                .collect(Collectors.toSet());

        assertThat(inMatrix)
                .as("这些命令没进「场景 × 权限」矩阵——增删命令时同步 USER-GUIDE 的标记块")
                .containsAll(registry.mainCommands().keySet());
    }

    /** 判据 2：矩阵的群限定行 = @BotCommand(groupOnly) 声明集合（文档侧同源）。 */
    @Test
    void groupOnlyRowsMatchDeclaredGroupOnlyCommands() throws IOException {
        Set<String> declared = registry.mainCommands().keySet().stream()
                .filter(registry::groupOnly)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(declared).as("groupOnly 声明不应为空（否则本判据是空转）").isNotEmpty();

        Set<String> marked = matrixLines().stream()
                .filter(line -> line.contains(GROUP_ONLY_MARK))
                .flatMap(line -> commandsIn(line).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(marked)
                .as("矩阵「只能在群里用」行的命令集合必须恰好等于 @BotCommand(groupOnly) 声明集合")
                .containsExactlyInAnyOrderElementsOf(declared);
    }
}
