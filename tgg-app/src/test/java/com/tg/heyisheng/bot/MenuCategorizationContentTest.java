package com.tg.heyisheng.bot;

import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import com.tg.heyisheng.bot.core.interaction.MenuVisibility;
import com.tg.heyisheng.bot.core.permission.Permission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分类完备性不变量（真容器里的全部命令）。
 *
 * <p><b>为什么需要它</b>：{@code /menu} 按 {@code @BotCommand.category()} 分组，默认
 * {@link MenuCategory#OTHER}——兜底是刻意的（fail-visible：宁可多一个「其他」，也不让命令从面板
 * 静默消失），但**长期泡在「其他」里说明有人加了命令却忘了分类**。这条测试把那种漂移变红，
 * 而不是等它悄悄堆进面板。
 *
 * <p>两条不变量：
 * ① 「能进 /menu 的命令」（带权限点 或 被某可见性接缝认领）必须有非 {@code OTHER} 的分类；
 * ② 接缝声明的命令必须真的注册过、并且有明确分类——接缝写错命令名会静默失效，这里拦住。
 *
 * <p><b>覆盖面说明</b>：默认测试配置下只有 core 的接缝在容器里（listing/federation 默认关闭），
 * 故模块六/八的命令由各自的 {@code *MenuVisibilityWiringTest} 覆盖。
 */
@SpringBootTest
class MenuCategorizationContentTest {

    /** 面板命令自身（不进目录，故不要求分类）。 */
    private static final String PANEL_COMMAND = "menu";

    @Autowired
    private CommandRegistry registry;

    @Autowired
    private List<MenuVisibility> menuVisibilities;

    @Test
    void everyCommandThatCanEnterTheMenuHasAnExplicitCategory() {
        List<String> uncategorized = registry.mainCommands().keySet().stream()
                .filter(name -> !PANEL_COMMAND.equals(name))
                .filter(name -> registry.requiredPermission(name) != Permission.NONE)
                .filter(name -> registry.categoryOf(name) == MenuCategory.OTHER)
                .toList();

        assertThat(uncategorized)
                .as("带权限点的命令必须在 @BotCommand 里声明 category，否则会全挤进「其他」分类")
                .isEmpty();
    }

    @Test
    void seamCommandsAreRegisteredAndCategorized() {
        Set<String> mainCommands = registry.mainCommands().keySet();

        assertThat(menuVisibilities).as("默认配置下 core 的接缝应当在容器里").isNotEmpty();
        for (MenuVisibility seam : menuVisibilities) {
            for (String command : seam.commands()) {
                assertThat(mainCommands)
                        .as("接缝声明的命令必须真的注册过（写错名字会静默失效）：%s", command)
                        .contains(command);
                assertThat(registry.categoryOf(command))
                        .as("接缝命令 %s 应声明明确分类", command)
                        .isNotEqualTo(MenuCategory.OTHER);
            }
        }
    }
}
