package com.tg.heyisheng.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.core.dispatch.CommandMenuRegistrar;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令菜单的**真实内容**（真容器里的全部 {@code @BotCommand}）。
 *
 * <p>两个不变量：
 * ① 每条主命令都必须有非空描述——否则它会**静默**从客户端菜单里消失（用户看不到该命令）；
 * ② 组装层不得过滤掉任何一条——过滤只应发生在「确实非法」时。
 *
 * <p>同时把序列化后的 {@code setMyCommands} 请求体写到 {@code target/command-menu.json}：
 * 部署时可用它核对 Telegram 侧的真实注册结果（`getMyCommands`）。
 */
@SpringBootTest
class CommandMenuContentTest {

    @Autowired
    private CommandRegistry registry;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void everyRegisteredCommandHasDescriptionAndSurvivesToMenu() throws Exception {
        Map<String, String> main = registry.mainCommands();

        assertThat(main).as("命令菜单至少要有几条").isNotEmpty();
        assertThat(main.entrySet())
                .as("命令缺描述 → 会从客户端菜单静默消失，必须补 @BotCommand(description=...)")
                .allSatisfy(e -> assertThat(e.getValue()).as(e.getKey()).isNotBlank());

        List<BotCommand> menu = CommandMenuRegistrar.toMenuCommands(main);
        assertThat(menu).as("组装层不得过滤任何一条合法命令").hasSize(main.size());
        assertThat(menu).extracting(BotCommand::getCommand).containsExactlyElementsOf(main.keySet());

        // 供部署核对：真正的请求体形状
        Path out = Path.of("target", "command-menu.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, objectMapper.writeValueAsString(Map.of("commands", menu)));
    }
}
