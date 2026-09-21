package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.common.model.UpdateContext;
import com.tg.heyisheng.bot.core.dispatch.BotCommand;
import com.tg.heyisheng.bot.core.dispatch.CommandHandler;
import com.tg.heyisheng.bot.core.dispatch.MenuCategory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

/**
 * {@code /whoami} —— 告诉用户他自己的 Telegram 身份（用户 ID，群内并给群 ID）。
 *
 * <p><b>收益直接的场景</b>：本项目的授权是配置驱动的（复核人 {@code TGG_MODERATION_REVIEWERS}、
 * 群管理员 {@code TGG_PERMISSION_ADMINS} 的 {@code 群ID:用户ID} 形式）——填写时**必须**先知道自己的数字 ID，
 * 而 Telegram 客户端不直接显示它。本命令把这两个数字摆到用户面前。
 *
 * <p><b>自助命令</b>（{@code publicCommand = true}、无权限点）：查的是自己的身份，凭什么要权限。
 * 与 {@code /echo}、{@code /quiet_hours} 同款，经 {@code /menu} 的「自助功能」分类到达
 * （客户端 {@code /} 菜单已收敛为只留 {@code /menu}，面板是唯一发现路径）。
 *
 * <p><b>隐私</b>：可见性判据收敛在 {@link IdentityPresenter}——私聊显示明文，群内默认只回引导
 * （对全群可见处不明文暴露用户 ID，见 {@code PRIVACY.md}）。运营者可经
 * {@value IdentityPresenter#GROUP_VISIBLE_KEY} 打开群内明文展示。
 *
 * <p><b>{@code worksWhenDisabled} 保持默认 false</b>：本命令不是「恢复类」命令，
 * 停用群内不生效——以维持「停用群的面板只列恢复类命令」这条既有不变量（见 {@code MenuCommandHandler}）。
 */
@BotCommand(value = "whoami", description = "查看你在本群的用户 ID 与群 ID",
        publicCommand = true, category = MenuCategory.SELF_SERVICE)
@Component
@ConditionalOnProperty(prefix = "tgg.interaction", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class WhoamiCommandHandler implements CommandHandler {

    private final IdentityPresenter identity;

    public WhoamiCommandHandler(IdentityPresenter identity) {
        this.identity = identity;
    }

    @Override
    public BotApiMethod<?> handle(UpdateContext ctx) {
        Long userId = ctx.userId();
        Long chatId = ctx.chatId();
        if (userId == null || chatId == null) {
            // 命令链上两者都应非空（AuthenticationMiddleware 要求 userId != null）；兜底不猜。
            return SendMessage.builder().chatId(String.valueOf(chatId))
                    .text("无法识别你的身份，请稍后再试。").build();
        }
        return SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(identity.whoamiReply(chatId, userId))
                .build();
    }
}
