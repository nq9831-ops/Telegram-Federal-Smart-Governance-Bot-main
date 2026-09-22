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
 *
 * <p><b>分头渲染（私聊 / 群聊，用户 2026-09-22 拍板）</b>：私聊版标题逐字「私聊可用的功能」、
 * 群限定命令单列「这些得到群里用」；群聊版维持现状结构并标注权限与群限定（「（得在群里用）」）。
 * 判据同源：群限定集合来自 {@code @BotCommand(groupOnly)} 声明，可见性来自 {@code MenuCatalog}。
 */
class HelpCommandHandlerTest {

    private static Map<MenuCategory, List<String>> grouped() {
        Map<MenuCategory, List<String>> grouped = new LinkedHashMap<>();
        grouped.put(MenuCategory.SELF_SERVICE, List.of("help", "whoami"));
        grouped.put(MenuCategory.MODERATION, List.of("words"));
        return grouped;
    }

    private static Map<String, String> descriptions() {
        return Map.of(
                "help", "按分类查看你能用的功能",
                "whoami", "查看你在本群的用户 ID",
                "words", "查看本群违禁词（需管理员权限）",
                "teach", "教一条本群审核规则（需 TEACH_RULE）",
                "group_tag", "管理本群话题标签（需 MANAGE_CONFIG）");
    }

    @Test
    void rendersCategoryHeadingsCommandNamesAndCounts() {
        String text = HelpCommandHandler.render(grouped(), descriptions(), false, List.of());

        assertThat(text).contains("共 3 条");
        assertThat(text).as("按分类分组，标题即分类名").contains("【自助功能】").contains("【内容审核】");
        assertThat(text).contains("/help —— 按分类查看你能用的功能");
        assertThat(text).contains("/whoami —— 查看你在本群的用户 ID");
    }

    /**
     * 权限括注**保留**——/help 承担「标注权限」的职责（用户 2026-09-22 拍板：
     * 「群聊版按现状并标注群限定与权限」）。/menu 按钮文案仍剥离（能看见就有权限，按钮空间有限）。
     *
     * <p>断言意图的前身为 {@code stripsPermissionNoteFromDescriptions}——设计翻转，断言随之翻转。
     */
    @Test
    void keepsPermissionNoteBecauseHelpAnnotatesRequiredPermission() {
        String text = HelpCommandHandler.render(grouped(), descriptions(), false, List.of());

        assertThat(text).contains("/words —— 查看本群违禁词（需管理员权限）");
    }

    /** 私聊版标题逐字「私聊可用的功能」，且不再自称「你在本群能用的功能」（自指错位）。 */
    @Test
    void privateChatTitleSaysPrivateUsable() {
        String text = HelpCommandHandler.render(grouped(), descriptions(), true, List.of());

        assertThat(text).contains("私聊可用的功能（共 3 条）");
        assertThat(text).as("私聊里说「你在本群」是自指错位").doesNotContain("你在本群能用的功能");
    }

    /** 私聊版把群限定命令单列「这些得到群里用」，不混进「私聊可用」分组。 */
    @Test
    void privateChatSeparatesGroupOnlyCommandsUnderDedicatedHeader() {
        String text = HelpCommandHandler.render(grouped(), descriptions(), true,
                List.of("teach", "group_tag"));

        assertThat(text).contains("这些得到群里用");
        assertThat(text).contains("\n/teach —— 教一条本群审核规则（需 TEACH_RULE）");
        assertThat(text).contains("\n/group_tag —— 管理本群话题标签（需 MANAGE_CONFIG）");
        assertThat(text).as("单列区不再叠「得在群里用」标记——标题已经说明白了")
                .doesNotContain("（得在群里用）");
    }

    /** 群聊版：现状结构 + 群限定命令行尾标注「（得在群里用）」（标注群限定）。 */
    @Test
    void groupChatMarksGroupOnlyCommandsInline() {
        Map<MenuCategory, List<String>> grouped = new LinkedHashMap<>();
        grouped.put(MenuCategory.MODERATION, List.of("teach"));

        String text = HelpCommandHandler.render(grouped, descriptions(), false, List.of("teach"));

        assertThat(text).contains("你在本群能用的功能（共 1 条）");
        assertThat(text).contains("/teach —— 教一条本群审核规则（需 TEACH_RULE）（得在群里用）");
    }

    /** 没写描述的命令不能因此变成空行——退化为只显示命令名。 */
    @Test
    void fallsBackToBareNameWithoutDescription() {
        String text = HelpCommandHandler.render(grouped(), Map.of(), false, List.of());

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

        String text = HelpCommandHandler.render(many, descriptions, false, List.of());

        assertThat(text.length()).isLessThan(HelpCommandHandler.TELEGRAM_TEXT_LIMIT);
        assertThat(text).as("截断要告诉用户去哪看全").contains("/menu 面板");
    }

    /** 私聊空态：拒绝给去路（R2），且不再出现「私聊我」这种自指文案。 */
    @Test
    void emptyPrivateChatGetsWayOutWithoutSelfReference() {
        String text = HelpCommandHandler.render(Map.of(), Map.of(), true, List.of());

        assertThat(text).contains("到群里发");
        assertThat(text).as("私聊里说「私聊我」是自指").doesNotContain("私聊我");
    }

    /** 群聊空态维持现状口径（引导去私聊问）。 */
    @Test
    void emptyGroupChatKeepsCurrentGuidance() {
        String text = HelpCommandHandler.render(Map.of(), Map.of(), false, List.of());

        assertThat(text).contains("私聊我");
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
