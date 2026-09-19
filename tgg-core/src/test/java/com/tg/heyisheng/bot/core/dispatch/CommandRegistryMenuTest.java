package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code CommandRegistry} 的「命令菜单」视图：主命令 + 描述（别名不入菜单）。
 *
 * <p>背景：{@code @BotCommand.description()} 自切片 1 起就写着「暂未使用」——29 条描述从未被消费，
 * 于是 Telegram 客户端里输入 {@code /} 没有任何提示（真实环境实测 `getMyCommands` 返回 0 条）。
 * 本测试钉住「从注册表能拿到菜单所需的两要素」，命令菜单才有唯一事实来源。
 */
class CommandRegistryMenuTest {

    @BotCommand(value = "echo", description = "连通性测试，回复固定文本", aliases = {"ping"})
    static class EchoHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return null;
        }
    }

    @BotCommand(value = "review_approve", description = "维持裁决")
    static class ApproveHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return null;
        }
    }

    @BotCommand(value = "no_desc")
    static class NoDescriptionHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return null;
        }
    }

    private static CommandRegistry registry() {
        return new CommandRegistry(List.of(new EchoHandler(), new ApproveHandler(), new NoDescriptionHandler()));
    }

    /** 主命令必须带描述，且**别名不得进菜单**（菜单里同时出现 echo 与 ping 是噪声）。 */
    @Test
    void mainCommandsCarriesDescriptionsAndExcludesAliases() {
        Map<String, String> menu = registry().mainCommands();

        assertThat(menu).containsOnlyKeys("echo", "review_approve", "no_desc");
        assertThat(menu).doesNotContainKey("ping");
        assertThat(menu.get("echo")).isEqualTo("连通性测试，回复固定文本");
    }

    /** 漏写描述的命令仍出现在视图里（描述为空串）——是否入菜单由组装层决定，注册表只陈述事实。 */
    @Test
    void missingDescriptionIsEmptyStringNotEmptyKey() {
        assertThat(registry().mainCommands()).containsEntry("no_desc", "");
    }

    /** 输出必须稳定有序（菜单顺序按名字，测试与日志才不会随机抖动）。 */
    @Test
    void mainCommandsAreSortedByName() {
        assertThat(registry().mainCommands().keySet())
                .containsExactly("echo", "no_desc", "review_approve");
    }

    @Test
    void mainCommandsViewIsUnmodifiable() {
        Map<String, String> menu = registry().mainCommands();
        assertThatThrownBy(() -> menu.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }
}
