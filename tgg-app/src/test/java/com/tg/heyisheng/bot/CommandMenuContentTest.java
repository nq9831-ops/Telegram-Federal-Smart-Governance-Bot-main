package com.tg.heyisheng.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.core.dispatch.CommandMenuRegistrar;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.interaction.MenuVisibility;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.RoleSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令菜单的**真实分档内容**（真容器里的全部 {@code @BotCommand} 与全部可见性接缝）。
 *
 * <p>三条不变量：
 * ① 每条已注册命令**要么出现在某一档、要么被判定为接缝类而刻意排除**——不许有命令
 * 「谁都没管」地消失（那种静默丢失正是本项目反复踩的坑）。
 * ② 接缝类（平台白名单）**不得出现在任何档**：Telegram 没有「全局按人」的 scope，
 * 向所有人广播 {@code review_approve} 这类命令名就是权限没做好。
 * ③ 默认档**只含公开命令**——带权限点的命令只对授权者可见。
 *
 * <p>同时把分档结果写到 {@code target/command-menu.json}：部署时可用它核对
 * Telegram 侧各 scope 的真实注册结果（{@code getMyCommands}）。
 */
@SpringBootTest
class CommandMenuContentTest {

    @Autowired
    private CommandRegistry registry;

    @Autowired
    private List<MenuVisibility> menuVisibilities;

    @Autowired
    private RoleSource roleSource;

    @Autowired
    private ObjectMapper objectMapper;

    private Set<String> seamCommands() {
        return menuVisibilities.stream()
                .flatMap(visibility -> visibility.commands().stream())
                .collect(Collectors.toSet());
    }

    private List<CommandMenuRegistrar.ScopedMenu> plan() {
        return CommandMenuRegistrar.planMenus(registry, seamCommands(), roleSource.grants());
    }

    private static Set<String> namesOf(CommandMenuRegistrar.ScopedMenu menu) {
        return menu.commands().stream().map(BotCommand::getCommand).collect(Collectors.toSet());
    }

    @Test
    void everyCommandIsEitherInSomeTierOrDeliberatelyExcluded() {
        Set<String> inTiers = plan().stream()
                .flatMap(menu -> namesOf(menu).stream())
                .collect(Collectors.toSet());
        Set<String> seamed = seamCommands();

        assertThat(inTiers).as("接缝类不得出现在任何档").doesNotContainAnyElementsOf(seamed);

        Set<String> accounted = new java.util.HashSet<>(inTiers);
        accounted.addAll(seamed);
        assertThat(accounted)
                .as("每条命令要么进某一档、要么被接缝刻意排除——不许静默消失")
                .containsExactlyInAnyOrderElementsOf(registry.mainCommands().keySet());
    }

    @Test
    void defaultTierHoldsOnlyPublicCommands() {
        List<CommandMenuRegistrar.ScopedMenu> menus = plan();

        assertThat(menus).as("至少有默认档").isNotEmpty();
        CommandMenuRegistrar.ScopedMenu defaultTier = menus.get(0);
        assertThat(defaultTier.label()).isEqualTo("default");
        assertThat(defaultTier.commands()).isNotEmpty();
        assertThat(defaultTier.commands()).allSatisfy(command ->
                assertThat(registry.requiredPermission(command.getCommand()))
                        .as("默认档里的 %s 不该带权限点", command.getCommand())
                        .isEqualTo(Permission.NONE));
    }

    /** 命令缺描述 → 会从该档静默消失（客户端渲染成空白行），必须补 {@code @BotCommand(description=...)}。 */
    @Test
    void everyRegisteredCommandHasDescription() {
        assertThat(registry.mainCommands())
                .as("命令缺描述 → 会从客户端菜单静默消失")
                .allSatisfy((name, description) -> assertThat(description).as(name).isNotBlank());
    }

    @Test
    void writesThePlanForDeploymentCrossCheck() throws Exception {
        List<Map<String, Object>> tiers = plan().stream().map(menu -> {
            Map<String, Object> tier = new LinkedHashMap<>();
            tier.put("label", menu.label());
            tier.put("scope", menu.scope().getType());
            tier.put("commands", menu.commands());
            return tier;
        }).toList();

        Path out = Path.of("target", "command-menu.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("tiers", tiers)));
    }
}
