package com.tg.heyisheng.bot.admin.config;

import com.tg.heyisheng.bot.admin.AdminApiTokenCondition;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 配置中心 · <b>只读总览</b>端点（模块十一 扩展）。
 *
 * <pre>
 * GET /admin/config   全量配置总览（分类 / 生效值 / 默认值 / 来源 / 是否需重启 / 是否可写）
 * </pre>
 *
 * <p><b>鉴权不在本类</b>：{@code AdminAuthFilter} 已按 {@code /admin/*} 前置验明 token 与
 * operator 白名单。本类只取用——门禁只有一处实现，才不会出现「某个端点漏判」。读取对**任一已鉴权操作者**开放（写权限才是更严的一层，见配置写端点）。
 *
 * <p><b>密钥不回显</b>：打码是 {@link RuntimeConfigService#snapshot()} 的契约（密钥键恒为
 * {@code ***} / {@code 未设置}），本类不额外处理，避免两处打码逻辑漂移。
 *
 * <p>与 {@code ApprovalController} 同样以 {@code @Conditional(AdminApiTokenCondition.class)}
 * 挂在控制器上：控制器是组件扫描注册的，不受配置类条件约束，只在一边挂条件会让上下文起不来。
 */
@RestController
@Conditional(AdminApiTokenCondition.class)
@RequestMapping(path = "/admin/config", produces = MediaType.APPLICATION_JSON_VALUE)
public class ConfigController {

    private final RuntimeConfigService config;

    public ConfigController(RuntimeConfigService config) {
        this.config = config;
    }

    /** 全量配置总览（只读）。 */
    @GetMapping
    public List<RuntimeConfigService.Resolved> list() {
        return config.snapshot();
    }
}
