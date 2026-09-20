package com.tg.heyisheng.bot.listing.merchant;

import com.tg.heyisheng.bot.core.config.dynamic.ConfigOverrideRepository;
import com.tg.heyisheng.bot.core.config.dynamic.RuntimeConfigService;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 测试替身：给 {@code ApplicationContextRunner} 型测试提供 {@link RuntimeConfigService}。
 *
 * <p><b>关键</b>：必须复用 runner 自己的 {@link Environment}（而不是另造一个空
 * {@code MockEnvironment}）——否则热读取的消费方看不到测试用 {@code withPropertyValues}
 * 设置的配置值（如 {@code tgg.merchant.reviewers} / {@code tgg.merchant.initial-score}）。
 *
 * <p>其 {@code @PostConstruct} 会尝试从 mock 仓库加载覆盖：mock 返回 {@code null} 触发 NPE，
 * 由 {@code RuntimeConfigService} 自身 catch 成 WARN，不影响上下文启动。
 */
@Configuration
public class RuntimeConfigTestStub {

    @Bean
    public RuntimeConfigService runtimeConfigService(Environment environment) {
        return new RuntimeConfigService(Mockito.mock(ConfigOverrideRepository.class), environment);
    }
}
