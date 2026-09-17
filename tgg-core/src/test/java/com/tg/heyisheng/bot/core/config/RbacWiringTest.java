package com.tg.heyisheng.bot.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.common.util.IdHasher;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandDispatcher;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.CommandRegistry;
import com.tg.heyisheng.bot.core.groupconfig.GroupConfigService;
import com.tg.heyisheng.bot.core.moderation.ModerationPipeline;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRecorder;
import com.tg.heyisheng.bot.core.permission.Permission;
import com.tg.heyisheng.bot.core.permission.Role;
import com.tg.heyisheng.bot.core.permission.RoleSource;
import com.tg.heyisheng.bot.core.webhook.WebhookProperties;
import com.tg.heyisheng.bot.core.wordfilter.BannedWordService;
import com.tg.heyisheng.bot.core.wordfilter.TaughtRuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RBAC 装配测试——防止「接好了但没通电」再次发生。
 *
 * <p>回归背景：切片 3b 交付时 {@code TggCoreConfiguration} 用的是
 * {@code new CommandDispatcher(registry)} 单参构造器，它会落到
 * 「恒最小权限」的兜底判定器，使权限门控在生产中完全不生效。
 * 本类从**容器里取出的那个 dispatcher** 出发做行为断言，因此能捕获这类装配错误。
 */
class RbacWiringTest {

    private static final long CHAT = -100L;
    private static final long ADMIN_USER = 42L;
    private static final long ORDINARY_USER = 999L;

    @BotCommand(value = "ban", requiredPermission = Permission.BAN_USER)
    static class BanHandler implements CommandHandler {
        @Override
        public BotApiMethod<?> handle(UpdateContext ctx) {
            // 必须返回非 null：dispatch 用 Optional.ofNullable 包装返回值，
            // 返回 null 会让"成功执行"与"未执行"在断言层面无法区分（本次就踩了这个坑）
            return new SendMessage(String.valueOf(ctx.chatId()), "ok");
        }
    }

    private ApplicationContextRunner runner(String adminsSpec) {
        return new ApplicationContextRunner()
                .withUserConfiguration(TggCoreConfiguration.class)
                .withBean(BanHandler.class, BanHandler::new)
                // ApplicationContextRunner 不做组件扫描，中间件链依赖的 service 需手工提供
                .withBean(GroupConfigService.class,
                        () -> org.mockito.Mockito.mock(GroupConfigService.class))
                // 发送通道装配依赖 ObjectMapper；ApplicationContextRunner 不做自动配置，需手工提供
                .withBean(ObjectMapper.class, ObjectMapper::new)
                // 复核入队通道是 @Service（组件扫描），ApplicationContextRunner 不扫描，需手工提供
                .withBean(ModerationReviewRecorder.class, ModerationReviewRecorder::noop)
                // 违禁词服务同样是 @Service；其 detector bean 由 TggCoreConfiguration 创建
                .withBean(BannedWordService.class,
                        () -> org.mockito.Mockito.mock(BannedWordService.class))
                // 四层审核流水线由 AiConfiguration 提供（本 runner 不加载它）——用空流水线占位：
                // 本测试关心的是权限门控装配，不是审核层
                .withBean(ModerationPipeline.class, () -> new ModerationPipeline(List.of()))
                // 教学规则仓库：TggCoreConfiguration 会据此创建 TaughtRuleService / TaughtRuleDetector，
                // 而 updateDispatcher 依赖后者——缺它整个上下文起不来（本测试不加载 JPA，须手工补）
                .withBean(TaughtRuleRepository.class,
                        () -> org.mockito.Mockito.mock(TaughtRuleRepository.class))
                // 模块十（通知）：装配依赖偏好服务与延迟队列仓库，二者都是 JPA 侧——
                // ApplicationContextRunner 不加载 JPA，须手工补替身（同 TaughtRuleRepository）。
                .withBean(com.tg.heyisheng.bot.core.notify.NotificationPreferenceService.class,
                        () -> org.mockito.Mockito.mock(
                                com.tg.heyisheng.bot.core.notify.NotificationPreferenceService.class))
                .withBean(com.tg.heyisheng.bot.core.notify.DeferredNotificationRepository.class,
                        () -> org.mockito.Mockito.mock(
                                com.tg.heyisheng.bot.core.notify.DeferredNotificationRepository.class))
                // 模块十（保留策略）：装配依赖复核队列与违规计数两个仓库，同样是 JPA 侧——补替身。
                .withBean(com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository.class,
                        () -> org.mockito.Mockito.mock(
                                com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository.class))
                .withBean(com.tg.heyisheng.bot.core.moderation.SensitiveTopicStrikeRepository.class,
                        () -> org.mockito.Mockito.mock(
                                com.tg.heyisheng.bot.core.moderation.SensitiveTopicStrikeRepository.class))
                // WebhookProperties 由 @EnableConfigurationProperties 创建，
                // 不能再 withBean 注册一份（否则出现两个同类型 bean 导致注入歧义）
                .withPropertyValues(
                        "tgg.webhook.secret=test-secret",
                        "tgg.permission.admins=" + adminsSpec);
    }

    @Test
    void configuredAdminPassesThePermissionGate() {
        runner(CHAT + ":" + ADMIN_USER + ":MODERATOR").run(context -> {
            // 分段断言：先确认配置确实进了角色源，再确认门控放行。
            // 合成一条断言的话，失败时分不清是"配置没生效"还是"装配没接上"。
            assertThat(context.getBean(RoleSource.class).roleOf(CHAT, ADMIN_USER))
                    .as("第一步：配置中的授权应进入 RoleSource")
                    .isEqualTo(Role.MODERATOR);

            assertThat(context.getBean(CommandRegistry.class).registeredCommands())
                    .as("第二步：受限命令应已注册到注册表")
                    .contains("ban");

            CommandDispatcher dispatcher = context.getBean(CommandDispatcher.class);
            assertThat(dispatcher.dispatch(ctx(ADMIN_USER)))
                    .as("第三步：授权用户应能执行受限命令——否则说明装配未注入真实判定器")
                    .isPresent();
        });
    }

    @Test
    void ordinaryUserIsRejectedByTheSameGate() {
        runner(CHAT + ":" + ADMIN_USER + ":MODERATOR").run(context -> {
            CommandDispatcher dispatcher = context.getBean(CommandDispatcher.class);

            assertThat(dispatcher.dispatch(ctx(ORDINARY_USER)))
                    .as("未授权用户必须被拦下——同一容器内取反断言，排除『门控整体失效』的假绿")
                    .isEmpty();
        });
    }

    @Test
    void withoutAnyGrantEveryoneIsRejected() {
        runner("").run(context -> {
            CommandDispatcher dispatcher = context.getBean(CommandDispatcher.class);

            assertThat(dispatcher.dispatch(ctx(ADMIN_USER))).isEmpty();
        });
    }

    /**
     * 哈希器 bean 必须由装配暴露——它承载「缺 TGG_HASH_SALT 时告警」这一环。
     * 若装配缺失，生产漏配盐会重新变成静默（usingDevFallbackSalt 的契约要求显式检查）。
     */
    @Test
    void idHasherBeanIsExposedByConfiguration() {
        runner(CHAT + ":" + ADMIN_USER + ":MODERATOR").run(context ->
                assertThat(context.getBean(IdHasher.class)).isNotNull());
    }

    private static UpdateContext ctx(long userId) {
        return new UpdateContext(1, userId, CHAT, "/ban");
    }
}
