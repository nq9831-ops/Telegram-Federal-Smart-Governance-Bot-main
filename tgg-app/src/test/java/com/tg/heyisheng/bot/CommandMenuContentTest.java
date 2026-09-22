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
 * <p>四条不变量：
 * ① **没有任何命令处于三不管地带**——「无权限点 + 未被接缝认领 + 未声明 {@code publicCommand}
 * 或 {@code clientMenu}」必须是空集。它意味着有人加了命令却没说它对谁可见；在当前 fail-closed
 * 规则下该命令既不在客户端菜单、也进不了 {@code /menu} 面板（不会泄露，但会静默地没人看得见），
 * 必须显式表态。
 * ② 接缝类（平台白名单）**不得出现在任何档**——Telegram 没有「全局按人」的 scope，
 * 向所有人广播 {@code review_approve} 这类命令名就是权限没做好。
 * ③ 客户端默认档**恰好等于**显式声明 {@code clientMenu} 的命令集合（不多不少）。
 * ④ 命令缺描述 → 会从该档静默消失，必须补 {@code @BotCommand(description = ...)}。
 *
 * <p>⚠️ 这里**刻意不用「并集覆盖」口径**（{@code inTiers ∪ seamed == 全部命令}）：它对
 * 「本该是平台门控却漏登记接缝、于是落进公开档」完全没有感觉——命令落进公开档时仍在
 * {@code inTiers} 里，并集照旧成立。故 ①②③ 都按**判据逐条复算**。
 *
 * <p>⚠️ <b>模块开关必须显式打开</b>：测试环境默认关闭 listing / merchant / federation，
 * 若不打开，注册表里只有 core 的 17 条命令——那几条公开命令的 {@code publicCommand} 声明
 * 就**无人校验**（删掉也不会有测试变红）。这正是本项目反复踩的「开关默认关闭 → 该路径长期假绿」。
 * federation 需要可解析的对端公钥（注解要求编译期常量），故其命令由
 * {@code FederationMenuVisibilityWiringTest} 覆盖，本类开启 listing + merchant。
 *
 * <p>同时把分档结果写到 {@code target/command-menu.json}：部署时可用它核对
 * Telegram 侧各 scope 的真实注册结果（{@code getMyCommands}）。
 */
@SpringBootTest(properties = {
        "tgg.listing.enabled=true",
        "tgg.merchant.enabled=true"
})
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

    private Set<String> commandsInSomeTier() {
        return plan().stream().flatMap(menu -> namesOf(menu).stream()).collect(Collectors.toSet());
    }

    /** ① fail-closed：不许有命令「谁都没管」——它必须能被某个入口到达。这条与测试环境有没有配置授权无关。 */
    @Test
    void noCommandIsLeftWithoutAnExplicitAudience() {
        Set<String> seamed = seamCommands();

        List<String> unreachable = registry.mainCommands().keySet().stream()
                .filter(name -> !seamed.contains(name))                                  // 接缝类由面板呈现
                .filter(name -> registry.requiredPermission(name) == Permission.NONE)    // 有权限点的走 RBAC 面板
                .filter(name -> !registry.publicCommand(name))                           // 自助命令进面板
                .filter(name -> !registry.clientMenu(name))                              // 客户端菜单入口
                .sorted()
                .toList();

        assertThat(unreachable)
                .as("这些命令不在客户端菜单、也无法经 /menu 面板到达——对谁都不可见。"
                        + "请显式声明 @BotCommand(publicCommand = true)（自助命令）或 clientMenu = true"
                        + "（客户端入口），或为其模块登记 MenuVisibility 接缝")
                .isEmpty();
    }

    /** ② 接缝类（平台白名单）不进任何档。 */
    @Test
    void seamCommandsNeverAppearInAnyTier() {
        Set<String> inTiers = commandsInSomeTier();

        assertThat(inTiers)
                .as("接缝类命令不得出现在任何客户端菜单档里（Telegram 无「全局按人」scope）")
                .doesNotContainAnyElementsOf(seamCommands());
    }

    /** ③ 客户端默认档恰好等于「显式 clientMenu 且无权限点」的集合——不多不少。 */
    @Test
    void clientMenuHoldsExactlyTheExplicitlyDeclaredEntries() {
        Set<String> expected = registry.mainCommands().keySet().stream()
                .filter(name -> !seamCommands().contains(name))
                .filter(registry::clientMenu)
                .collect(Collectors.toSet());

        List<CommandMenuRegistrar.ScopedMenu> menus = plan();
        assertThat(menus).as("至少有默认档").isNotEmpty();
        CommandMenuRegistrar.ScopedMenu defaultTier = menus.get(0);
        assertThat(defaultTier.label()).isEqualTo("default");

        assertThat(namesOf(defaultTier)).as("客户端默认档 = 显式声明 clientMenu 的命令")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(defaultTier.commands()).allSatisfy(command ->
                assertThat(registry.requiredPermission(command.getCommand()))
                        .as("客户端菜单里的 %s 不该带权限点（带权限点会向无权者暴露其存在）",
                                command.getCommand())
                        .isEqualTo(Permission.NONE));
    }

    /** ④ 命令缺描述 → 会从该档静默消失（客户端渲染成空白行）。 */
    @Test
    void everyRegisteredCommandHasDescription() {
        assertThat(registry.mainCommands())
                .as("命令缺描述 → 会从客户端菜单静默消失")
                .allSatisfy((name, description) -> assertThat(description).as(name).isNotBlank());
    }

    /**
     * ⑤ 群限定声明与硬门的同步钉：{@code @BotCommand(groupOnly = true)} 必须**恰好**是
     * handler 内 {@code chatId >= 0} 硬拒（GROUP_ONLY）的命令集合——当前三条
     * （teach / group_tag / listing_add）。增删硬门而不同步声明（或反之）会让 /help·/menu
     * 的分场景渲染漂移：私聊里列出点了却无声的命令，或漏列「这些得到群里用」。
     */
    @Test
    void groupOnlyDeclarationIsExactlyTheHardGatedTrio() {
        Set<String> declared = registry.mainCommands().keySet().stream()
                .filter(registry::groupOnly)
                .collect(Collectors.toSet());

        assertThat(declared)
                .as("群限定声明应恰好是三条硬门命令；增删时同步 @BotCommand(groupOnly)、handler 硬门与本断言")
                .containsExactlyInAnyOrder("teach", "group_tag", "listing_add");
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
