# 安全文档（SECURITY）

> 面向**部署方与审计者**：本项目**实际**做了哪些安全控制、为什么这么做、以及**哪些没做**。
> 未实现的部分一律明写——请勿假定本项目提供了未列出的能力。
> 合规视角见 `COMPLIANCE.md`；架构视角见 `ARCHITECTURE.md`。

## 一、威胁模型与范围

本项目是**群组治理机器人**，保护目标按优先级：

| 保护对象 | 主要威胁 | 对应控制 |
|---|---|---|
| 群成员的**消息内容** | 被日志/数据库留存、被外发 | 正文零存储、`MessageScrubber`、标识哈希、L3 默认关闭 |
| 机器人的**管理面** | 伪造 webhook 请求、越权执行管理命令 | 常量时间 secret 校验、RBAC 门控、白名单 |
| 服务的**可用性** | 消息洪泛、管理员可写正则导致的 ReDoS | 三级令牌桶限流、正则三道闸门 |
| **审计链** | 处置记录被篡改或删除 | `audit_log` 应用层无删除入口 |

**不在范围内**：资金托管、制裁名单筛查、KYC/旅行规则、未成年人年龄核验。逐条见 `COMPLIANCE.md` 开篇。

## 二、认证

**Webhook 入口**（唯一的入站通道）：

- `SecretTokenFilter` 校验 `X-Telegram-Bot-Api-Secret-Token`（**Bot 库本身不校验该头**）。
- 比对用 `MessageDigest.isEqual` 做**常量时间**比较（`SecretTokenVerifier:36`），避免按字节短路比较
  泄露前缀信息。
- **`TGG_WEBHOOK_SECRET` 缺失即拒绝启动**：`WebhookProperties:34` 抛
  `TggConfigException("TGG_WEBHOOK_SECRET 未配置 —— 拒绝以无校验状态启动")`。这是刻意的 fail-fast，
  **不要改成默认值**。

**管理面**（模块十一，可选启用）：

- `Authorization: Bearer <tgg.admin.api-token>` + `X-Operator-Id` 头，两层缺一不可：
  token 证明「够得着后台」，`X-Operator-Id` 必须落在 `TGG_MODERATION_REVIEWERS` 白名单内，
  证明「有权审批」。
- **token 未配置（或为空白）时整个端点不装配**——访问得 **404**（端点不存在），而非 403。
  这是刻意的 fail-closed（`AdminApiTokenCondition` 用自定义 `SpringBootCondition` 判非空，
  因为 `@ConditionalOnProperty` 会把「属性存在但为空串」也算匹配）。
- token 是**共享密钥、不指向具体的人**（每个运维一个凭据需改为 token→userId 映射，**未实现**）。

## 三、授权

**模型**（自研，未引 Spring Security）：`Permission{NONE, BAN_USER, MANAGE_CONFIG, TEACH_RULE}`
→ `PermissionChecker` 判定 → 来源 `RoleSource`。

**四条独立的授权通道**（不要混用）：

| 通道 | 载体 | 为空时的行为 |
|---|---|---|
| 群内管理员 | `TGG_PERMISSION_ADMINS`，格式 `<chatId>:<userId>[:role]` | **所有管理命令对任何人不可用**（门控只拒不放 + 启动 WARN） |
| 复核人 | `TGG_MODERATION_REVIEWERS` | `/review_*` 与 `/admin/*` 的审批人身份不可用 |
| 联邦管理员 | `TGG_FEDERATION_ADMINS` | `/pending`·`/approve`·`/reject` 不可用 |
| 商家复核人 | `TGG_MERCHANT_REVIEWERS` | `/merchant_review`·`/merchant_deposit` 不可用 |

**两条不得削弱的约束**：

1. `CommandDispatcher` 必须注入**真实**的 `PermissionChecker`——单参构造器会落到「恒最小权限」的兜底
   实现，使权限门控在生产中**完全不生效**（该构造器仅供未装配场景与单测）。
2. **审批人不可审批自己的案件**（模块十一 §12.2 约束）：`X-Operator-Id` 等于案件当事人 → **403**，
   且状态不变。内建的 `/review_*` **没有**这条校验（原文约束写在「Web后台」章节下）。

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
| webhook 异常不引发重试风暴 | 全局异常处理器把异常吞成 200（**代价**：未知路径也返回 200 而非 404；模块十一的 404/403 用 `ResponseEntity` 显式设码绕开） |
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
| **漏洞扫描** | `tools/security/scan_dependencies.py` 读 SBOM 查 OSV；**网络失败 fail-closed**（绝不给「0 漏洞」假绿灯）；CI 中退出码 1 即失败 |
| 已处置 | tomcat-embed-core 10.1.55→10.1.60、jackson-databind 2.21.4→2.21.5、commons-lang3 3.17.0→3.18.0、log4j-api 2.24.3→2.25.5（3×CRITICAL + 5×MEDIUM → 重扫 0 命中） |
| ⚠️ 边界 | 扫描只覆盖**公开收录**的漏洞；**基础镜像的 OS 包未扫**（需 trivy/grype 等，未接） |

## 十、部署加固

- 容器以 **非 root（uid 10001）** 运行；`Dockerfile` 多阶段构建（构建工具不进运行镜像）。
- 数据库端口在 `docker-compose.yml` 中**只绑 `127.0.0.1`**，不对公网暴露。
- ⚠️ **Actuator / CSP / HSTS 未实现**：本项目**未引入 Actuator**（容器健康检查只探 TCP 端口），
  CSP/HSTS 建议在**反向代理层**终止 TLS 时统一配置。
- ⚠️ **无 K8s 清单**（原文 §17 要求；Compose 已覆盖单机场景）。

## 十一、报告安全问题

请**不要**在公开 issue 中披露可利用的漏洞。通过仓库的私有安全报告渠道联系维护者，并附上：
复现步骤、影响面、以及你验证时所处的版本（`git rev-parse HEAD`）。
