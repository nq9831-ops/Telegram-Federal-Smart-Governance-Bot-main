package com.tg.heyisheng.bot.core.config.dynamic;

import java.util.List;
import java.util.Optional;

/**
 * 配置键目录——配置中心的**单一事实来源**。
 *
 * <p>它把「本项目有哪些配置」这件事从散落的 {@code @Value} / {@code @ConfigurationProperties} /
 * {@code application.yml} 里显式抽出来，集中成一份可被读端点、写校验、前端三处共用的声明。
 * 这本身即解决本项目反复出现的一类缺陷——「开关默认关闭 → 功能静默失效」，
 * 因为每条都显式写明了「当前值 / 默认值 / 不设会怎样」。
 *
 * <p><b>刻意是静态工具（无 Spring 依赖）</b>：写入校验与单元测试都不必启动上下文。
 *
 * <p><b>边界（刻意的设计取舍）</b>：本目录只覆盖**属性型**配置（{@code tgg.*} / {@code spring.datasource.*}）。
 * 群内自治配置（{@code group_configs} / {@code banned_words} / {@code group_topic_tags}）是
 * <b>按群的行</b>而非「键→值」，形状不同，**不并入本目录**——它们的入口仍是群内命令，
 * 与 ARCHITECTURE §1「群内事务由群管理员决定」一致。
 */
public final class ConfigCatalog {

    private ConfigCatalog() {
    }

    private static final List<ConfigKey> KEYS = List.of(
            // ─────────── ⓐ 引导 / 基础设施（只读 + 打码）───────────
            new ConfigKey("spring.datasource.url", ConfigCategory.BOOTSTRAP, ConfigValueType.STRING,
                    false, true, false, "jdbc:mysql://localhost:3306/tgg", null, null, null,
                    "数据库连接串。应用启动的前提，只读。"),
            new ConfigKey("spring.datasource.username", ConfigCategory.BOOTSTRAP, ConfigValueType.STRING,
                    false, true, false, "tgg", null, null, null,
                    "数据库用户。应用启动的前提，只读。"),
            new ConfigKey("spring.datasource.password", ConfigCategory.BOOTSTRAP, ConfigValueType.STRING,
                    false, true, true, null, null, null, null,
                    "数据库密码。无默认值，缺失即连接失败（刻意 fail-fast）。只读、不回显。"),
            new ConfigKey("tgg.webhook.secret", ConfigCategory.BOOTSTRAP, ConfigValueType.STRING,
                    false, true, true, null, null, null, null,
                    "Webhook 的 X-Telegram-Bot-Api-Secret-Token 校验值。缺失即启动失败（刻意 fail-fast）。只读。"),
            new ConfigKey("tgg.hash.salt", ConfigCategory.BOOTSTRAP, ConfigValueType.STRING,
                    false, true, true, null, null, null, null,
                    "userId 哈希盐。不设会退回开发兜底盐（userId 空间小，可被枚举反推）。只读。"),

            // ─────────── ⓑ 密钥 / 凭据（只读；只回显「已设/未设」）───────────
            new ConfigKey("tgg.webhook.bot-token", ConfigCategory.SECRET, ConfigValueType.STRING,
                    false, true, true, null, null, null, null,
                    "Bot token。主动调用（准入验证 / 硬红线封禁 / 下架通知 / 链接探针）需要；缺失时各自降级并 WARN。"),
            new ConfigKey("tgg.credit.private-key", ConfigCategory.SECRET, ConfigValueType.STRING,
                    false, true, true, null, null, null, null,
                    "模块七 Ed25519 私钥（PKCS#8 Base64）。启用信用分时必填，缺失即启动失败。"),
            new ConfigKey("tgg.ai.deepseek.api-key", ConfigCategory.SECRET, ConfigValueType.STRING,
                    false, true, true, null, null, null, null,
                    "L3 DeepSeek API key。启用 L3 时必填，缺失即启动失败。"),
            new ConfigKey("tgg.admin.api-token", ConfigCategory.SECRET, ConfigValueType.STRING,
                    false, true, true, null, null, null, null,
                    "审批中心 Bearer 令牌（后台自身的门禁）。为空则 /admin/* 端点整体不装配。"),
            new ConfigKey("tgg.federation.nodes", ConfigCategory.SECRET, ConfigValueType.STRING,
                    false, true, true, null, null, null, null,
                    "模块八对端节点清单（url|公钥Base64，逗号分隔）。启用联邦时必填且非空。含对端公钥，按敏感处理。"),

            // ─────────── ⓒ 装配开关（可写，改后**需重启**）───────────
            new ConfigKey("tgg.command-menu.enabled", ConfigCategory.ASSEMBLY, ConfigValueType.BOOLEAN,
                    true, true, false, "true", null, null, null,
                    "启动期把命令清单按权限分档注册到 Telegram 客户端菜单。唯一默认开启的开关。"),
            new ConfigKey("tgg.interaction.enabled", ConfigCategory.ASSEMBLY, ConfigValueType.BOOLEAN,
                    true, true, false, "true", null, null, null,
                    "交互卡片（/menu 面板、危险操作确认卡）。默认开启（matchIfMissing）。"),
            new ConfigKey("tgg.failover.enabled", ConfigCategory.ASSEMBLY, ConfigValueType.BOOLEAN,
                    true, true, false, "false", null, null, null,
                    "Webhook 失效后降级长轮询。启用需 TGG_BOT_TOKEN，否则启动失败。"),
            new ConfigKey("tgg.admission.enabled", ConfigCategory.ASSEMBLY, ConfigValueType.BOOLEAN,
                    true, true, false, "false", null, null, null,
                    "入群验证 + 观察期。启用需 TGG_BOT_TOKEN。"),
            new ConfigKey("tgg.ai.deepseek.enabled", ConfigCategory.ASSEMBLY, ConfigValueType.BOOLEAN,
                    true, true, false, "false", null, null, "tgg.ai.deepseek.api-key",
                    "L3 云端审核：启用后消息正文会发送至第三方。缺 api-key 即启动失败。"),
            new ConfigKey("tgg.ai.local.l2-enabled", ConfigCategory.ASSEMBLY, ConfigValueType.BOOLEAN,
                    true, true, false, "false", null, null, null,
                    "L2 本地 ML 分类器（依赖部署方自备端点）。"),
            new ConfigKey("tgg.ai.local.l4-enabled", ConfigCategory.ASSEMBLY, ConfigValueType.BOOLEAN,
                    true, true, false, "false", null, null, null,
                    "L4 本地零样本层（依赖部署方自备端点）。"),
            new ConfigKey("tgg.credit.enabled", ConfigCategory.ASSEMBLY, ConfigValueType.BOOLEAN,
                    true, true, false, "false", null, null, "tgg.credit.private-key",
                    "模块七信用分体系。启用需 Ed25519 私钥，缺失即启动失败。"),
            new ConfigKey("tgg.federation.enabled", ConfigCategory.ASSEMBLY, ConfigValueType.BOOLEAN,
                    true, true, false, "false", null, null, "tgg.federation.nodes",
                    "模块八联邦治理。启用需非空且可解析的节点清单，否则启动失败。"),
            new ConfigKey("tgg.listing.enabled", ConfigCategory.ASSEMBLY, ConfigValueType.BOOLEAN,
                    true, true, false, "false", null, null, null,
                    "模块五群组收录（含每日链接验证任务）。"),
            new ConfigKey("tgg.merchant.enabled", ConfigCategory.ASSEMBLY, ConfigValueType.BOOLEAN,
                    true, true, false, "false", null, null, null,
                    "模块六商家收录与保证金。"),
            new ConfigKey("tgg.moderation.sensitive-grading-enabled", ConfigCategory.ASSEMBLY, ConfigValueType.BOOLEAN,
                    true, true, false, "false", null, null, null,
                    "敏感话题分级与递进处置（受群标签豁免）。"),
            new ConfigKey("tgg.retention.enabled", ConfigCategory.RUNTIME, ConfigValueType.BOOLEAN,
                    true, false, false, "false", null, null, null,
                    "数据保留策略：启用后会自动删除业务数据（审计表永不清理）。热生效（RetentionJob 在每轮清理时读取）。"),

            // ─────────── ⓓ 运行期参数 ───────────
            // 已改造为「调用期读取」→ 热生效：
            new ConfigKey("tgg.admin.overdue-remind-hours", ConfigCategory.RUNTIME, ConfigValueType.HOURS,
                    true, false, false, "24", 1, 8760, null,
                    "审批待办超过该小时数 → 提醒。热生效（无需重启）。"),
            new ConfigKey("tgg.admin.overdue-escalate-hours", ConfigCategory.RUNTIME, ConfigValueType.HOURS,
                    true, false, false, "72", 1, 8760, null,
                    "审批待办超过该小时数 → 升级提醒。热生效（无需重启）。"),
            new ConfigKey("tgg.interaction.whoami-group-visible", ConfigCategory.RUNTIME, ConfigValueType.BOOLEAN,
                    true, false, false, "false", null, null, null,
                    "是否在群里明文展示用户 ID（/whoami 的群内回复与 /menu 面板身份行）。默认 false："
                            + "对全群可见处只得引导去私聊，明文 ID 仅在私聊给出。热生效（无需重启）。"),
            // 未改造 → 诚实标注「重启生效」：
            new ConfigKey("tgg.admin.restart-enabled", ConfigCategory.RUNTIME, ConfigValueType.BOOLEAN,
                    true, false, false, "false", null, null, null,
                    "是否允许经 Web 触发优雅重启。无外部监管进程的部署应保持 false（点了按钮＝停服不起）。热生效。"),
            new ConfigKey("tgg.admin.config-admins", ConfigCategory.RUNTIME, ConfigValueType.CSV_IDS,
                    true, false, false, "", null, null, null,
                    "可写配置/触发重启的 userId 白名单。为空则回落到 TGG_MODERATION_REVIEWERS。热生效。"),
            new ConfigKey("tgg.moderation.reviewers", ConfigCategory.RUNTIME, ConfigValueType.CSV_IDS,
                    true, false, false, "", null, null, null,
                    "复核人 userId 白名单。为空则 /review_* 对任何人不可用（启动 WARN）。热生效。"),
            new ConfigKey("tgg.permission.admins", ConfigCategory.RUNTIME, ConfigValueType.ROLE_GRANTS,
                    true, false, false, "", null, null, null,
                    "群内管理员授权（<chatId>:<userId>[:role]）。为空则所有管理命令对任何人不可用。热生效（格式非法会被写入校验拒绝）。"),
            new ConfigKey("tgg.federation.admins", ConfigCategory.RUNTIME, ConfigValueType.CSV_IDS,
                    true, false, false, "", null, null, null,
                    "联邦管理员 userId 白名单。为空则 /pending·/approve·/reject 不可用。热生效。"),
            new ConfigKey("tgg.merchant.reviewers", ConfigCategory.RUNTIME, ConfigValueType.CSV_IDS,
                    true, false, false, "", null, null, null,
                    "商家资质复核人 userId 白名单。为空则 /merchant_review·/merchant_deposit 不可用。热生效。"),
            new ConfigKey("tgg.merchant.initial-score", ConfigCategory.RUNTIME, ConfigValueType.INT,
                    true, false, false, "500", 0, 1000, null,
                    "商家入驻成功时写入的初始信用分（需模块七启用）。热生效。"),
            new ConfigKey("tgg.teach.min-membership-days", ConfigCategory.RUNTIME, ConfigValueType.LONG,
                    true, false, false, "30", 0, 3650, null,
                    "教学门槛 · 入群时长（天）。热生效。"),
            new ConfigKey("tgg.moderation.redline-review-sla-hours", ConfigCategory.RUNTIME, ConfigValueType.HOURS,
                    true, false, false, "2", 1, 8760, null,
                    "§10.6 硬红线复核 SLA（小时）。超时只催办、不自动解封。热生效。"),
            new ConfigKey("tgg.retention.reviewed-queue-days", ConfigCategory.RUNTIME, ConfigValueType.INT,
                    true, false, false, "90", 0, 3650, null,
                    "已裁决复核队列的保留天数。热生效。"),
            new ConfigKey("tgg.retention.strike-days", ConfigCategory.RUNTIME, ConfigValueType.INT,
                    true, false, false, "365", 0, 3650, null,
                    "敏感话题计数的保留天数。热生效。"),
            new ConfigKey("tgg.retention.membership-days", ConfigCategory.RUNTIME, ConfigValueType.INT,
                    true, false, false, "365", 0, 3650, null,
                    "入群观察数据的保留天数（兜底，正常靠退群即删）。热生效。"),

            // ─────────── ⓓ 补充：此前漏登记、只出现在 application.yml 里的键 ───────────
            // 它们不影响功能，但会让「配置中心」给出「都在这儿」的错觉（本轮审查抓到）。
            // ⚠️ cron 一律标「需重启」：@Scheduled(cron = "${...}") 在**装配期**就把表达式固定了，
            //    写库不会重排任务——诚实标注比假装热生效重要。
            new ConfigKey("tgg.admin.config-cache-ttl-seconds", ConfigCategory.RUNTIME, ConfigValueType.INT,
                    false, true, false, "10", 0, 3600, null,
                    "配置覆盖缓存的 TTL（秒）：跨副本传播的最大延迟；0=只在启动与本地写入时加载。"
                            + "只读——它在读路径上使用，若自身可写会形成「读配置以决定如何读配置」的递归。"),
            new ConfigKey("tgg.admin.overdue-cron", ConfigCategory.RUNTIME, ConfigValueType.CRON,
                    true, true, false, "0 15 * * * *", null, null, null,
                    "审批超时扫描的 cron。改动需重启（@Scheduled 在装配期固定）。"),
            new ConfigKey("tgg.moderation.redline-sla-cron", ConfigCategory.RUNTIME, ConfigValueType.CRON,
                    true, true, false, "0 5 * * * *", null, null, null,
                    "§10.6 红线复核 SLA 催办的 cron。改动需重启。"),
            new ConfigKey("tgg.breach.remind-cron", ConfigCategory.RUNTIME, ConfigValueType.CRON,
                    true, true, false, "0 0 * * * *", null, null, null,
                    "数据泄露 72 小时通报催办的 cron。改动需重启。"),
            new ConfigKey("tgg.retention.cron", ConfigCategory.RUNTIME, ConfigValueType.CRON,
                    true, true, false, "0 30 4 * * *", null, null, null,
                    "保留策略每日执行的 cron。改动需重启。"),
            new ConfigKey("tgg.listing.verify-cron", ConfigCategory.RUNTIME, ConfigValueType.CRON,
                    true, true, false, "0 0 3 * * *", null, null, null,
                    "收录链接每日验证的 cron（仅模块五启用时生效）。改动需重启。"),
            new ConfigKey("tgg.admission.timeout-seconds", ConfigCategory.RUNTIME, ConfigValueType.INT,
                    true, true, false, "120", 1, 86400, null,
                    "入群验证的超时秒数（仅模块四启用时生效）。改动需重启。"),
            new ConfigKey("tgg.admission.observation-seconds", ConfigCategory.RUNTIME, ConfigValueType.INT,
                    true, true, false, "604800", 0, 31536000, null,
                    "新成员观察期秒数（默认 7 天）。改动需重启。"),
            new ConfigKey("tgg.listing.fail-threshold", ConfigCategory.RUNTIME, ConfigValueType.INT,
                    true, true, false, "3", 1, 100, null,
                    "收录链接连续失败几次判失效并下架。改动需重启。"),
            new ConfigKey("tgg.listing.retry-times", ConfigCategory.RUNTIME, ConfigValueType.INT,
                    true, true, false, "2", 0, 10, null,
                    "收录链接验证失败后的重试次数。改动需重启。"),
            new ConfigKey("tgg.listing.retry-interval-minutes", ConfigCategory.RUNTIME, ConfigValueType.INT,
                    true, true, false, "5", 1, 1440, null,
                    "收录链接验证重试的间隔分钟数。改动需重启。"),
            new ConfigKey("tgg.listing.dispute-window-days", ConfigCategory.RUNTIME, ConfigValueType.INT,
                    true, true, false, "7", 0, 3650, null,
                    "收录下架的异议期天数。改动需重启。"),
            new ConfigKey("tgg.failover.failure-threshold", ConfigCategory.RUNTIME, ConfigValueType.INT,
                    true, true, false, "3", 1, 100, null,
                    "降级判定：连续几次探测不健康即切换长轮询（仅模块二启用时生效）。改动需重启。"),
            new ConfigKey("tgg.failover.error-window-seconds", ConfigCategory.RUNTIME, ConfigValueType.INT,
                    true, true, false, "300", 1, 86400, null,
                    "降级健康探测的错误统计窗口秒数。改动需重启。"),
            new ConfigKey("spring.datasource.driver-class-name", ConfigCategory.BOOTSTRAP, ConfigValueType.STRING,
                    false, true, false, "com.mysql.cj.jdbc.Driver", null, null, null,
                    "JDBC 驱动类名。应用启动的前提，只读。")
    );

    /** 全部键（只读视图）。 */
    public static List<ConfigKey> keys() {
        return KEYS;
    }

    /** 按键名查元数据。 */
    public static Optional<ConfigKey> find(String key) {
        return KEYS.stream().filter(k -> k.key().equals(key)).findFirst();
    }
}
