package com.tg.heyisheng.bot.core.config;

import com.tg.heyisheng.bot.core.membership.MemberJoinObservationRepository;
import com.tg.heyisheng.bot.core.moderation.ModerationReviewRepository;
import com.tg.heyisheng.bot.core.moderation.SensitiveTopicStrikeRepository;
import com.tg.heyisheng.bot.core.retention.RetentionJob;
import com.tg.heyisheng.bot.core.retention.RetentionProperties;
import com.tg.heyisheng.bot.core.retention.RetentionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 模块十 · 数据保留策略装配（§11.2）。
 *
 * <p><b>为什么从 {@code TggCoreConfiguration} 拆出来</b>：那一类曾装配 29 个 bean，
 * 覆盖命令与权限、审核、通知、保留、教学门槛、web 入口六个互不相关的域。按域拆开后
 * 每类可独立阅读，改动一个域不必通读其余部分。
 *
 * <p><b>拆分不影响装配</b>：{@code @Configuration} 类之间靠方法参数互相注入，
 * Spring 不关心某个 bean 定义在哪个类里（只要仍在组件扫描范围内）。
 * {@link RetentionProperties} 的注册随本域一并搬到此处（原本在 {@code TggCoreConfiguration} 的
 * {@code @EnableConfigurationProperties} 里），使本类自洽可独立加载。
 */
@Configuration
@EnableConfigurationProperties(RetentionProperties.class)
public class RetentionConfiguration {

    /**
     * 数据保留策略（模块十 §11.2）——<b>默认只报告不清理</b>（{@code tgg.retention.enabled=false}）。
     *
     * <p>bean 刻意不受开关门控：即便不清理，每日报告也要照常打出——
     * 运维得先看到「会发生什么」，才谈得上决定是否放行。
     */
    @Bean
    public RetentionService retentionService(ModerationReviewRepository reviewRepository,
                                             SensitiveTopicStrikeRepository strikeRepository,
                                             MemberJoinObservationRepository memberJoinRepository,
                                             RetentionProperties retentionProperties) {
        return new RetentionService(reviewRepository, strikeRepository, memberJoinRepository,
                retentionProperties, Clock.systemUTC());
    }

    @Bean
    public RetentionJob retentionJob(RetentionService retentionService) {
        return new RetentionJob(retentionService);
    }
}
