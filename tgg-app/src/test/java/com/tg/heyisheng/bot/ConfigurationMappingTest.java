package com.tg.heyisheng.bot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code application.yml} 的键映射守门测试。
 *
 * <p><b>它防的是什么</b>：Spring 的宽松绑定会把 {@code verifyCron} 推导成环境变量
 * {@code TGG_LISTING_VERIFYCRON}（**去掉连字符**），而文档惯用下划线写法
 * （{@code TGG_LISTING_VERIFY_CRON}）——**两者不一致，且拼错时不会有任何报错**：
 * 运维按文档设了变量，应用却读不到，表现为「配置了却不生效」的静默失效。
 * 本项目在 {@code tgg.credit.private-key} 上踩过同一个坑（当时靠显式映射修掉）。
 *
 * <p>因此这里直接读**真实的 yml 文件**，逐个断言「键名 → 期望的环境变量名」。
 * 这不是脑补式的静态检查：yml 改了、键拼错了、漏写了，都会让本测试变红。
 *
 * <p>本测试不启动 Spring 上下文（纯 yml 解析），因此运行开销极小。
 */
class ConfigurationMappingTest {

    /**
     * 生产 yml 的路径。
     *
     * <p>⚠️ <b>必须读文件、不能走 classpath</b>：{@code src/test/resources/application.yml} 会
     * <b>遮蔽</b> {@code src/main/resources} 的同名文件（测试 classpath 优先 test-classes），
     * 用它读会得到测试专用配置——本测试要守的生产键一个都看不到。这一点由探针实测确认
     * （classpath 读到的是 `test-secret-value`，且 `tgg.listing.*` 全为 null）。
     */
    private static final Path PRODUCTION_YML = Paths.get("src/main/resources/application.yml");

    private static PropertySource<?> yaml() throws Exception {
        assertThat(PRODUCTION_YML)
                .as("找不到生产 yml——路径写错时必须显式失败，否则本守门测试会静默通过")
                .exists();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new FileSystemResource(PRODUCTION_YML));
        assertThat(sources).as("application.yml 应能被解析出至少一个文档").isNotEmpty();
        return sources.get(0);
    }

    /** 模块五：开关 + 全部多词键都要显式映射。 */
    @Test
    void listingKeysAreExplicitlyMapped() throws Exception {
        PropertySource<?> yml = yaml();

        assertThat(yml.getProperty("tgg.listing.enabled")).isEqualTo("${TGG_LISTING_ENABLED:false}");
        assertThat(yml.getProperty("tgg.listing.verify-cron"))
                .as("多词键必须显式映射到 TGG_LISTING_VERIFY_CRON（否则会落空到 TGG_LISTING_VERIFYCRON）")
                .isEqualTo("${TGG_LISTING_VERIFY_CRON:0 0 3 * * *}");
        assertThat(yml.getProperty("tgg.listing.fail-threshold")).isEqualTo("${TGG_LISTING_FAIL_THRESHOLD:3}");
        assertThat(yml.getProperty("tgg.listing.retry-times")).isEqualTo("${TGG_LISTING_RETRY_TIMES:2}");
        assertThat(yml.getProperty("tgg.listing.retry-interval-minutes"))
                .isEqualTo("${TGG_LISTING_RETRY_INTERVAL_MINUTES:5}");
        assertThat(yml.getProperty("tgg.listing.dispute-window-days"))
                .isEqualTo("${TGG_LISTING_DISPUTE_WINDOW_DAYS:7}");
    }

    /** 模块六：开关 + 初始分（多词键）+ 复核人白名单。 */
    @Test
    void merchantKeysAreExplicitlyMapped() throws Exception {
        PropertySource<?> yml = yaml();

        assertThat(yml.getProperty("tgg.merchant.enabled")).isEqualTo("${TGG_MERCHANT_ENABLED:false}");
        assertThat(yml.getProperty("tgg.merchant.initial-score"))
                .as("多词键必须显式映射到 TGG_MERCHANT_INITIAL_SCORE")
                .isEqualTo("${TGG_MERCHANT_INITIAL_SCORE:500}");
        assertThat(yml.getProperty("tgg.merchant.reviewers")).isEqualTo("${TGG_MERCHANT_REVIEWERS:}");
    }

    /** 模块四：准入与验证的两个多词键也要显式映射（本波次补齐，此前 yml 里没有 admission 段）。 */
    @Test
    void admissionKeysAreExplicitlyMapped() throws Exception {
        PropertySource<?> yml = yaml();

        assertThat(yml.getProperty("tgg.admission.enabled")).isEqualTo("${TGG_ADMISSION_ENABLED:false}");
        assertThat(yml.getProperty("tgg.admission.timeout-seconds"))
                .as("多词键必须显式映射（否则按 TGG_ADMISSION_TIMEOUT_SECONDS 设的值会静默落空）")
                .isEqualTo("${TGG_ADMISSION_TIMEOUT_SECONDS:120}");
        assertThat(yml.getProperty("tgg.admission.observation-seconds"))
                .isEqualTo("${TGG_ADMISSION_OBSERVATION_SECONDS:604800}");
    }

    /** 主开关默认必须为 false——「默认关闭」是本项目的装配契约，不该被 yml 悄悄改写。 */
    @Test
    void moduleSwitchesDefaultToDisabled() throws Exception {
        PropertySource<?> yml = yaml();

        assertThat(String.valueOf(yml.getProperty("tgg.listing.enabled"))).contains("false");
        assertThat(String.valueOf(yml.getProperty("tgg.merchant.enabled"))).contains("false");
    }
}
