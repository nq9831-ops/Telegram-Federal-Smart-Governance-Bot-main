package com.tg.heyisheng.bot.admin;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 装配条件：{@code tgg.admin.api-token} 有实际值（模块十一）。
 *
 * <p><b>为什么是独立类而不是内嵌在 {@link AdminConfiguration} 里</b>：{@code ApprovalController}
 * 是 {@code @RestController}，会被<b>组件扫描</b>独立注册——它不受另一个类上的条件约束。
 * 若只在配置类挂条件，未配 token 时控制器照样被创建，却找不到由条件配置提供的服务，
 * 结果是<b>整个应用上下文起不来</b>（本波实测踩到）。故条件必须挂在<b>每一个</b>本模块的组件上，
 * 而共享条件只能在类级别复用——与项目在命令处理器上的既有做法一致。
 *
 * <p><b>为什么不用 {@code @ConditionalOnProperty}</b>：后者把「属性存在但为空串」也算作匹配，
 * 于是 {@code TGG_ADMIN_API_TOKEN=}（显式留空）会让后台带着空令牌上线。
 * 空令牌意味着第一层门禁形同虚设，而这是最容易发生的误配。
 */
public class AdminApiTokenCondition extends SpringBootCondition {

    /** 配置键（同时用于错误信息，便于运维定位）。 */
    public static final String TOKEN_PROPERTY = "tgg.admin.api-token";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context,
                                            AnnotatedTypeMetadata metadata) {
        String token = context.getEnvironment().getProperty(TOKEN_PROPERTY);
        if (token == null || token.isBlank()) {
            return ConditionOutcome.noMatch(TOKEN_PROPERTY + " 未配置（或为空白）——模块十一端点不装配");
        }
        return ConditionOutcome.match(TOKEN_PROPERTY + " 已配置");
    }
}
