package com.tg.heyisheng.bot.core.dispatch;

import com.tg.heyisheng.bot.common.exception.TggDispatchException;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandRegistryTest {

    @BotCommand(value = "echo", aliases = {"ping"})
    static class EchoStub implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return null;
        }
    }

    @BotCommand(value = "echo")
    static class ConflictingStub implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            return null;
        }
    }

    @BotCommand("orphan")
    static class NotAHandler {
    }

    @Test
    void registersCommandAndAliases() {
        CommandRegistry registry = new CommandRegistry(List.of(new EchoStub()));

        assertThat(registry.registeredCommands()).containsExactlyInAnyOrder("echo", "ping");
        assertThat(registry.find("/echo")).isPresent();
        assertThat(registry.find("ping")).isPresent();
        assertThat(registry.find("/ECHO")).as("归一化后大小写不敏感").isPresent();
    }

    @Test
    void normalizesBotUsernameSuffix() {
        CommandRegistry registry = new CommandRegistry(List.of(new EchoStub()));

        assertThat(registry.find("/echo@MyGovernanceBot")).isPresent();
    }

    @Test
    void rejectsConflictingCommandNames() {
        assertThatThrownBy(() -> new CommandRegistry(List.of(new EchoStub(), new ConflictingStub())))
                .isInstanceOf(TggDispatchException.class)
                .hasMessageContaining("冲突");
    }

    @Test
    void rejectsAnnotatedBeanThatIsNotAHandler() {
        assertThatThrownBy(() -> new CommandRegistry(List.of(new NotAHandler())))
                .isInstanceOf(TggDispatchException.class)
                .hasMessageContaining("CommandHandler");
    }

    @Test
    void unknownCommandIsEmpty() {
        CommandRegistry registry = new CommandRegistry(List.of(new EchoStub()));

        assertThat(registry.find("/nope")).isEmpty();
    }
}
