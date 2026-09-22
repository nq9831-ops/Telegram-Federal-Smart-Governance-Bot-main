# 安全文档（SECURITY）

> 面向**部署方与审计者**：本项目**实际**做了哪些安全控制、为什么这么做、以及**哪些没做**。
> 未实现的部分一律明写——请勿假定本项目提供了未列出的能力。

## 一、威胁模型与范围

本项目是**群组治理机器人**，保护目标按优先级：

| 保护对象 | 主要威胁 | 对应控制 |
|---|---|---|
| 群成员的**消息内容** | 被日志/数据库留存、被外发 | 正文零存储、`MessageScrubber`、标识哈希、L3 默认关闭 |
| 机器人的**管理面** | 伪造 webhook 请求、越权执行管理命令 | 常量时间 secret 校验、RBAC 门控、白名单 |
| 服务的**可用性** | 消息洪泛、管理员可写正则导致的 ReDoS | 三级令牌桶限流、正则三道闸门 |
| **审计链** | 处置记录被篡改或删除 | `audit_log` 应用层无删除入口 |

**不在范围内**：资金托管、制裁名单筛查、KYC/旅行规则、未成年人年龄核验。运营者须自行确认其运营行为满足所在法域的相应要求。

## 二、认证

**Webhook 入口**（唯一的入站通道）：

- `SecretTokenFilter` 校验 `X-Telegram-Bot-Api-Secret-Token`（**Bot 库本身不校验该头**）。
- 比对用 `MessageDigest.isEqual` 做**常量时间**比较（`SecretTokenVerifier:36`），避免按字节短路比较
  泄露前缀信息。
- **`TGG_WEBHOOK_SECRET` 缺失即拒绝启动**：`WebhookProperties:34` 抛
  `TggConfigException("TGG_WEBHOOK_SECRET 未配置 —— 拒绝以无校验状态启动")`。这是刻意的 fail-fast，
  **不要改成默认值**。

**管理面**（模块十一，可选启用）：

- **服务端会话**：`POST /admin/auth/login`（账号 + 密码，或 Telegram Login Widget 验签）签发**不透明令牌**；
  之后一律带 `Authorization: Bearer <会话令牌>`，请求头**不携带任何身份**——
  主体（类型 / id / 角色）由服务端按令牌反查。登出 / 停用 / 改密 / 强制下线**即时生效**。
- **`tgg.admin.api-token` 已退化为「装配开关」**：非空才装配 `/admin/*`；它**不再**参与鉴权。
  全仓只有 `AdminApiTokenCondition`（装配门）与 `ConfigCatalog`（打码回显）读它。
- **`X-Operator-Id` 已彻底移除**：它曾用于携带「审批人是谁」，但客户端可伪造；
  身份改由服务端会话持有后不再需要。带它不会被识别，也不影响请求。
- **api-token 未配置（或为空白）时整个端点不装配**——访问得 **404**（端点不存在），而非 403。
  这是刻意的 fail-closed（`AdminApiTokenCondition` 用自定义 `SpringBootCondition` 判非空，
  因为 `@ConditionalOnProperty` 会把「属性存在但为空串」也算匹配）。

## 三、授权

**模型**（自研，未引 Spring Security）：`Permission{NONE, BAN_USER, MANAGE_CONFIG, TEACH_RULE}`
→ `PermissionChecker` 判定 → 来源 `RoleSource`。

**四条独立的授权通道**（不要混用）：

| 通道 | 载体 | 为空时的行为 |
|---|---|---|
| 群内管理员 | `TGG_PERMISSION_ADMINS`，格式 `<chatId>:<userId>[:role]` | **所有管理命令对任何人不可用**（门控只拒不放 + 启动 WARN） |
| 复核人 | `TGG_MODERATION_REVIEWERS` | `/review_*` 与 `/admin/*` 的审批人身份不可用 |
| 联邦管理员 | `TGG_FEDERATION_ADMINS` | `/pending`·`/approve`·`/reject` 不可用 |
| ~~商家复核人~~（通道已裁撤 2026-09-22） | 无——现归**联邦管理员**（`TGG_FEDERATION_ADMINS`） | 「商家只有联邦管理员才可以审核处理」：`/merchant_review`·`/merchant_deposit`·`/merchant_settle` 仅联邦管理员可用 |

**两条不得削弱的约束**：

1. `CommandDispatcher` 必须注入**真实**的 `PermissionChecker`——单参构造器会落到「恒最小权限」的兜底
   实现，使权限门控在生产中**完全不生效**（该构造器仅供未装配场景与单测）。
2. **不可自审**（模块十一 §12.2 约束）：裁决人恰是被判定消息的发布者时拒绝——Web 后台返回
   **403**（`self-decision-forbidden`），群内 `/review_approve`·`/review_reject` 回「不能裁决自己的
   案件」；两种入口**都不改状态、不发动作**。规则实现在**共用的裁决服务**
   （`ModerationReviewDecisionService.decide`）里而非任何适配层——它一度只写在 Web 侧，
   代价是「群里能审自己的案子、后台不能审」的两端漂移。

## 四、隐私（数据最小化）

| 控制 | 实现 |
|---|---|
| **消息正文零存储** | 表结构与实体**无承载正文的字段**；`UpdateContext` 刻意只带路由元数据 |
| **内存中及时清除** | `MessageScrubber` 在 `finally` 中清除 `text`/`caption`（含投票、地点、链接预览字段）；放在 `finally` 是因为**异常路径**最容易被日志带出正文 |
| **日志脱敏** | 用户/群 id 一律经 `IdHasher`（HMAC-SHA256）哈希；审核命中日志只记规则 id 与风险等级，**不记命中片段** |
| **正文外发默认关闭** | L3 云端审核（DeepSeek）默认关闭；启用即意味着**正文出境**，须自行完成告知/同意（见 `PRIVACY.md`） |
| **数据主体查阅** | `/export_my_data`——只导出**发起者本人**的通知偏好与审计记录 |
| **数据主体删除** | ⚠️ **无自助删除**。审计表刻意不可删（合规留痕），其余数据由保留策略按期限清理 |
| **哈希盐** | `TGG_HASH_SALT` 不设会退回**开发兜底盐**（userId 空间小，固定盐可被枚举反推）——生产**必须**配置 |

## 五、输入安全

**管理员可写的正则是高危输入**（能编译 / 长度受限 / 拒绝嵌套量词——一条 `(a+)+` 就足以让单条消息
拖垮整个机器人）。`TaughtRuleService` 对 `/teach` 的正则过**三道闸门**：

1. 能编译；
2. 长度受限（`MAX_REGEX_LENGTH = 512`）；
3. 拒绝灾难性回溯形态（`containsNestedQuantifier`，用**括号栈解析**判定「组被量词修饰且组内含被量词修饰
   元素」；不用简单字符串匹配——那会漏判两层嵌套 `((a+))+`，并把字符类内的 `+` 与转义括号误判为嵌套）。

**其他**：持久化一律走 JPA 参数化查询；命令操作数经 `UpdateContext.commandArgs()`，非命令消息一律 `null`
（不让它成为正文的侧路）。

## 六、可用性

| 控制 | 值 / 行为 |
|---|---|
| 三级限流 | 用户 **20** / 群 **60** / 全局 **200**，窗口 **10 秒**（`InMemoryRateLimiter`） |
| webhook 异常不引发重试风暴 | 全局异常处理器把**业务异常**吞成 200（**未知路径例外**：`NoResourceFoundException` 由同一处理器放行为 404，见 `UnknownPathIT`；模块十一的 404/403 用 `ResponseEntity` 显式设码——业务异常仍会被吞，故不能靠抛异常表达状态码） |
| webhook 失效降级 | `TGG_FAILOVER_ENABLED=true` 时降级长轮询（默认关闭） |
| 群级熔断 | 未 `/enable` 的群一律拒绝 |
| 定时任务可诊断 | 所有 `@Scheduled` 任务**无条件留入口日志**——「没被调度」与「调度了但查到 0 条」外部表现相同，入口留痕是可诊断性的一部分 |

## 七、密钥管理

| 项 | 现状 |
|---|---|
| 注入方式 | **环境变量**；代码中不硬编码任何密钥 |
| 必填密钥缺失 | **fail-fast 拒绝启动**（`TGG_WEBHOOK_SECRET`；`TGG_CREDIT_PRIVATE_KEY` 在信用分启用时同样） |
| 可选能力缺失 | **显式降级 + WARN**（如 `TGG_BOT_TOKEN` 缺失 → 封禁不可用但删除仍生效），**不静默** |
| Vault 集成 | ⚠️ **未实现**（用环境变量替代） |
| 密钥轮换 | ⚠️ **未实现**（属运营流程，非代码能力；原文建议 90 天） |
| 泄露 API key | 用户已确认自行吊销，代码侧无残留 |

**签名**：模块七的处罚令用 **Ed25519**（PKCS#8 私钥经环境变量注入）；联邦入站请求**验签**后才落库。

## 八、审计

- 每次命令处理器执行经 **AOP 切面**（`AuditAspect`，`@Around` 覆盖 `com.tg.heyisheng.bot..*.handle(..)`）
  写入 `audit_log`（`actorId` / `action` / `target` / `outcome` / `detail`）。
- 切面用 `AopProxyUtils.ultimateTargetClass` 读目标类——直接用 `getClass()` 会读到代理类。
- 审计写入 **fail-open**：只记 ERROR，不中断业务。
- **`audit_log` 在应用层没有删除入口**（保留策略硬编码排除该表）——合规章节要求的「不可删」由此保证。

## 九、供应链

| 控制 | 实现 |
|---|---|
| 依赖版本统一 | 父 POM 的 `dependencyManagement` + 属性覆盖 |
| **SBOM** | `mvn package` 生成聚合 `target/bom.json`（CycloneDX 1.5，`cyclonedx-maven-plugin`） |
| **漏洞扫描** | `mvn package` 产出聚合 SBOM（`target/bom.json`，CycloneDX），可交任意 OSV 兼容扫描器。**建议**接入 CI，并对网络失败 fail-closed（绝不给「0 漏洞」假绿灯） |
| 已处置 | tomcat-embed-core 10.1.55→10.1.60、jackson-databind 2.21.4→2.21.5、commons-lang3 3.17.0→3.18.0、log4j-api 2.24.3→2.25.5（3×CRITICAL + 5×MEDIUM → 重扫 0 命中） |
| ⚠️ 边界 | 扫描只覆盖**公开收录**的漏洞；**基础镜像的 OS 包未扫**（需 trivy/grype 等，未接） |

## 十、部署加固

- 容器以 **非 root（uid 10001）** 运行；`Dockerfile` 多阶段构建（构建工具不进运行镜像）。
- 数据库端口在 `docker-compose.yml` 中**只绑 `127.0.0.1`**，不对公网暴露。
- ✅ **Actuator 已引入并锁定**（原文 §15.2）：只暴露 `/actuator/health`（其余端点一律关闭，
  `show-details=never`），容器 `HEALTHCHECK` 因此探的是**业务就绪**而非仅端口在听。
  由 `ActuatorLockedIT`（行为）与 `ConfigurationMappingTest.actuatorIsLockedDownInProduction`（生产配置）两层守门。
- ✅ **安全响应头**：应用对所有响应补 `X-Content-Type-Options: nosniff` / `X-Frame-Options: DENY` /
  `Referrer-Policy: no-referrer`（`SecurityHeadersFilter`），含 401 等短路路径（`SecurityHeadersIT` 覆盖）。
- ⚠️ **CSP / HSTS 仍不在应用层**，且这是刻意的：`Strict-Transport-Security` 必须由 **TLS 终止方**
  （反向代理 / Ingress）下发；`Content-Security-Policy` 约束页面加载，而后台静态站**不由 Spring 托管**
  （`frontend/` 是独立静态站，不经 Spring 托管），应用加它对页面无效；两者均须在**反向代理**配置中落地。
- ⚠️ **K8s 清单已提供但未经集群验证**（`k8s/`）：交付时只做了 YAML 结构解析，**未在任何集群上验证过**。

## 十一、报告安全问题

请**不要**在公开 issue 中披露可利用的漏洞。通过仓库的私有安全报告渠道联系维护者，并附上：
复现步骤、影响面、以及你验证时所处的版本（`git rev-parse HEAD`）。
