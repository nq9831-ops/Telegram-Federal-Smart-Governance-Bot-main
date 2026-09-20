package com.tg.heyisheng.bot.core.dispatch;

/**
 * 命令描述的**呈现清洗**（一处实现，两处共用）。
 *
 * <p>{@code @BotCommand(description)} 里常带权限括注（「（需管理员权限）」「(平台复核人)」），
 * 而它有两个消费方：**客户端 {@code /} 菜单**（发给 Telegram 的 {@code BotCommand}）与
 * **{@code /menu} 卡片**（按钮文案）。这两个入口**都已按权限过滤过**——能看见这条命令的人
 * 必然有权限，再写一遍括注既是噪声、又把按钮撑长。
 *
 * <p>故清洗只写一份、放在 dispatch（依赖链底层）：{@code MenuView} 引用它，而不是各写一份正则。
 * 各写一份的代价是两处迟早不一致——同一条命令在两个入口显示不同的文案，用户会以为是两条命令。
 */
public final class CommandDescriptions {

    private CommandDescriptions() {
    }

    /**
     * 去掉描述**结尾**的括注；{@code null} 视为空串。
     *
     * <p><b>为什么只按位置判断、而不识别括注内容</b>：识别「这是权限说明而不是用法」需要维护一份
     * 关键词表（需/权限/复核人/白名单…），那种表必然随新命令悄悄过期——本项目已多次吃过这个亏。
     * 项目里带括注的描述几乎全是权限类（「（需管理员权限）」「(平台复核人)」），按位置一刀切即可。
     *
     * <p><b>代价（如实记录）</b>：结尾若是**用法**示例也会被一并去掉。
     * 故描述**不该把用法写在结尾的括注里**——写成「设置免打扰时段，例：/quiet_hours 22:00-08:00」这类
     * 不含括注的形式即可（{@code /quiet_hours} 已如此调整）。中段的括注不受影响。
     */
    public static String stripPermissionNote(String description) {
        if (description == null) {
            return "";
        }
        return description.replaceAll("[（(][^（()）]*[)）]\\s*$", "").trim();
    }
}
