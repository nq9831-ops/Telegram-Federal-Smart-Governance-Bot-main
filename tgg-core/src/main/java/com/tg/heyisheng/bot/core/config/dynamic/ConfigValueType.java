package com.tg.heyisheng.bot.core.config.dynamic;

/**
 * 配置值的类型——用于**服务端**校验（不能只靠前端）。
 *
 * <p>类型一旦声明，写入时即按此校验：非数字的 INT、越界的 HOURS 一律拒绝，
 * 避免非法值落库后在下次启动 fail-fast 把应用砖掉。
 */
public enum ConfigValueType {

    /** 自由文本（如 {@code tgg.permission.admins} 的 {@code <chatId>:<userId>[:role]} 串）。 */
    STRING,

    /** 布尔：只接受 {@code true} / {@code false}。 */
    BOOLEAN,

    /** 整数（带可选上下界）。 */
    INT,

    /** 长整数（带可选上下界）。 */
    LONG,

    /** 小时数：整数、≥ 1（调用方按 {@code Duration.ofHours} 使用）。 */
    HOURS,

    /** 逗号分隔的 userId 白名单（如 {@code tgg.moderation.reviewers}）。 */
    CSV_IDS,

    /**
     * 角色授权串（{@code <chatId>:<userId>[:role]}，逗号分隔，如 {@code tgg.permission.admins}）。
     *
     * <p>单独成类型是为了<b>写入即校验</b>：该配置的格式若非法，{@code RoleGrantParser} 会在解析时抛异常。
     * 热化后解析移到了调用期，若不在此拦截，一条笔误会在下次启动或下次命令执行时才炸——
     * 故写入时先解析一次（见 {@code RuntimeConfigService#validateValue}）。
     */
    ROLE_GRANTS
}
