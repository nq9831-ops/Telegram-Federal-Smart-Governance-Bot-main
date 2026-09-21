package com.tg.heyisheng.bot.core.interaction;

import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;

/**
 * 身份信息的**展示与隐私策略**——{@code /whoami} 命令与 {@code /menu} 面板身份行共用这一份实现。
 *
 * <p><b>为什么抽成一个类</b>：用户 ID 的「哪里能显示、哪里不能」是一条隐私判据，
 * 若命令与面板各写一份条件，迟早漂移成「面板遮住了、命令却漏出去」（本项目已有
 * 「两处各写一份条件就会漂移」的教训）。判据只写一份。
 *
 * <p><b>判据（与 {@code PRIVACY.md} 全群可见口径一致）</b>：
 * <ul>
 *   <li><b>私聊</b>（Telegram 里 {@code chatId == userId}）——一对一，永远显示明文用户 ID；</li>
 *   <li><b>群内</b>——默认**不**显示明文用户 ID（群消息对全群可见，且面板卡片可被转发），
 *       只回一句引导去私聊；除非把 {@value #GROUP_VISIBLE_KEY} 置为 true（运营者显式选择）。</li>
 * </ul>
 *
 * <p><b>开关是热读的</b>：经 {@link RuntimeConfigService} 在**调用期**读取，
 * 后台改配置即刻生效、无需重启（配置中心里该键标注为「热生效」）。
 */
public class IdentityPresenter {

    /** 「群内是否明文展示用户 ID」的配置键。默认 false（隐私优先）。 */
    public static final String GROUP_VISIBLE_KEY = "tgg.interaction.whoami-group-visible";

    /** 群内隐藏时的引导文案（指向私聊）。 */
    static final String GROUP_HIDDEN_HINT = InteractionMessages.IDENTITY_GROUP_HIDDEN;

    private final RuntimeConfigService runtimeConfig;

    public IdentityPresenter(RuntimeConfigService runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    /** 群内是否明文展示用户 ID。 */
    boolean groupVisible() {
        return runtimeConfig.getBoolean(GROUP_VISIBLE_KEY, false);
    }

    /** {@code /whoami} 的回复文案。 */
    public String whoamiReply(long chatId, long userId) {
        return whoamiReply(chatId, userId, groupVisible());
    }

    /** {@code /menu} 面板顶部的身份行；群内隐藏时返回空串（不加噪声）。 */
    public String menuIdentityLine(long chatId, long userId) {
        return menuIdentityLine(chatId, userId, groupVisible());
    }

    /**
     * Telegram 里私聊的 {@code chatId} 等于用户自己的 {@code userId}——这是区分私聊与群聊最省事的判据，
     * 无需额外的 ChatType 字段（{@code UpdateContext} 刻意只带路由元数据）。
     */
    static boolean isPrivate(long chatId, long userId) {
        return chatId == userId;
    }

    /** 纯函数形态（便于单测，不依赖容器）。 */
    static String whoamiReply(long chatId, long userId, boolean groupVisible) {
        if (isPrivate(chatId, userId)) {
            return InteractionMessages.identityPrivate(userId);
        }
        if (groupVisible) {
            return InteractionMessages.identityGroup(chatId, userId);
        }
        return GROUP_HIDDEN_HINT;
    }

    /** 纯函数形态（便于单测，不依赖容器）。 */
    static String menuIdentityLine(long chatId, long userId, boolean groupVisible) {
        if (isPrivate(chatId, userId) || groupVisible) {
            return InteractionMessages.identityLine(userId);
        }
        return "";
    }
}
