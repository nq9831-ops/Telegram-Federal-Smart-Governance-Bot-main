package com.tg.heyisheng.bot.core.config.dynamic;

/**
 * 配置的<b>语义分类</b>——决定它在配置中心里「能不能写」。
 *
 * <p>这是配置中心的核心洞察：「所有配置统一管理」这句话里，配置其实是四类性质完全不同的东西，
 * 混在一起管理等于把密钥、启动前提与运行参数拉平成同一种可写文本。
 */
public enum ConfigCategory {

    /**
     * 引导 / 基础设施：应用**启动的前提**（数据源、webhook secret）。
     * 后台自己要先能起来才轮得到它管配置——故只读。
     */
    BOOTSTRAP,

    /**
     * 密钥 / 凭据：写进 Web 等于把密钥同时放进数据库、浏览器与审计明细，且其中
     * {@code tgg.admin.api-token} 还是后台自己的门禁（自管＝自锁死）。故只读、只回显「已设/未设」。
     */
    SECRET,

    /**
     * 装配开关：{@code @ConditionalOnProperty} 的模块开关，**启动期求值**。
     * 可写，但变更**需重启生效**（由启动期注入器读回，见 ConfigOverrideEnvironmentPostProcessor）。
     */
    ASSEMBLY,

    /**
     * 运行期参数：阈值、时长、白名单等。**其中已改造为「调用期读取」的才真正热生效**；
     * 未改造的在总览里诚实标注「重启生效」，绝不谎报为热。
     */
    RUNTIME
}
