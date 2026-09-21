package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /help} 的文本渲染。
 *
 * <p>可见性判定与分组在 {@code MenuCatalog}（本命令刻意复用、不另写一套），
 * 故此处只钉「拿到分组与描述之后，渲染出的文本对不对」。
 */
class HelpCommandHandlerTest {

    private static Map<MenuCategory, List<String>> grouped() {
        Map<MenuCategory, List<String>> grouped = new LinkedHashMap<>();
        grouped.put(MenuCategory.SELF_SERVICE, List.of("help", "whoami"));
        grouped.put(MenuCategory.MODERATION, List.of("words"));
        return grouped;
    }

    @Test
    void rendersCategoryHeadingsCommandNamesAndCounts() {
        String text = HelpCommandHandler.render(grouped(), Map.of(
                "help", "按分类查看你能用的功能",
                "whoami", "查看你在本群的用户 ID",
                "words", "查看本群违禁词（需管理员权限）"));

        assertThat(text).contains("共 3 条");
        assertThat(text).as("按分类分组，标题即分类名").contains("【自助功能】").contains("【内容审核】");
        assertThat(text).contains("/help —— 按分类查看你能用的功能");
        assertThat(text).contains("/whoami —— 查看你在本群的用户 ID");
    }

    /** 说明里的权限括注必须去掉——菜单已按权限过滤，能看见就有权限，再写一遍是噪声（与 /menu 同口径）。 */
    @Test
    void stripsPermissionNoteFromDescriptions() {
        String text = HelpCommandHandler.render(grouped(), Map.of(
                "help", "按分类查看你能用的功能",
                "whoami", "查看你在本群的用户 ID",
                "words", "查看本群违禁词（需管理员权限）"));

        assertThat(text).contains("/words —— 查看本群违禁词");
        assertThat(text).doesNotContain("需管理员权限");
    }

    /** 没写描述的命令不能因此变成空行——退化为只显示命令名。 */
    @Test
    void fallsBackToBareNameWithoutDescription() {
        String text = HelpCommandHandler.render(grouped(), Map.of());

        assertThat(text).contains("\n/help\n").doesNotContain("/help —— ");
    }

    /** 超长必须截断并指路——Bot API 单条上限 4096，超长整条发送失败（回复直接丢掉）。 */
    @Test
    void truncatesWhenTooLongInsteadOfSendingAnOverlongMessage() {
        // 必须**确定性地**越过 4096 阈值：早先版本只用 3 条 ×1000 字符（共约 3000），
        // 压根没触发截断分支，断言于是测了个寂寞。这里用够多条目让总长必然超限。
        Map<MenuCategory, List<String>> many = new LinkedHashMap<>();
        Map<String, String> descriptions = new java.util.HashMap<>();
        java.util.List<String> commands = new java.util.ArrayList<>();
        String longDesc = "很长的说明".repeat(20);
        for (int i = 0; i < 60; i++) {
            String cmd = "cmd_" + i;
            commands.add(cmd);
            descriptions.put(cmd, longDesc);
        }
        many.put(MenuCategory.MODERATION, commands);

        String text = HelpCommandHandler.render(many, descriptions);

        assertThat(text.length()).isLessThan(HelpCommandHandler.TELEGRAM_TEXT_LIMIT);
        assertThat(text).as("截断要告诉用户去哪看全").contains("/menu 面板");
    }

    /** 命令元数据：自助命令（对全体成员可见），否则 CommandMenuContentTest 的 fail-closed 会判它「对谁都不可见」。 */
    @Test
    void commandIsPublicSelfService() {
        com.tg.heyisheng.bot.core.dispatch.BotCommand meta =
                HelpCommandHandler.class.getAnnotation(com.tg.heyisheng.bot.core.dispatch.BotCommand.class);

        assertThat(meta).isNotNull();
        assertThat(meta.value()).isEqualTo("help");
        assertThat(meta.publicCommand()).isTrue();
        assertThat(meta.description()).isNotBlank();
        assertThat(meta.category()).isEqualTo(MenuCategory.SELF_SERVICE);
    }
}
