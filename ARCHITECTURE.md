# 架构文档（ARCHITECTURE）

> 面向**开发者与审计者**：系统由哪些部分组成、一次更新如何流动、关键决策**为什么**这样做。
> 使用说明见 `README.md`，安全设计见 `SECURITY.md`，合规边界见 `COMPLIANCE.md`。

## 一、定位与治理哲学

**群主自治管群，联邦守住底线。**

- **群内事务**（违禁词、教学规则、功能开关）由**群管理员**经命令决定，机器人只执行；
- **跨群底线**（硬红线冻结、联邦标记）由**平台层**兜底，不依赖任何单个群主的意愿。

项目定位是**开源软件**：开发者交付代码与文档，**不参与运营**——不提供资金托管、不做制裁筛查、
不做 KYC（逐条见 `COMPLIANCE.md` 开篇的「本项目没有做什么」）。

## 二、模块划分与依赖方向

7 个 Maven 模块，依赖严格单向（各模块 `pom.xml` 实证）：

```mermaid
flowchart TD
  APP["tgg-app<br/>启动器 · 可运行 jar"]
  ADMIN["tgg-admin<br/>模块十一 · 审批中心 API"]
  LISTING["tgg-listing<br/>模块五/六 · 收录与保证金"]
  FED["tgg-federation<br/>模块八 · 联邦治理"]
  CREDIT["tgg-credit<br/>模块七 · 信用分"]
  CORE["tgg-core<br/>模块一/三/四/九/十"]
  COMMON["tgg-common<br/>模型 · 工具"]

  APP --> ADMIN
  APP --> LISTING
  APP --> FED
  APP --> CORE
  ADMIN --> CORE
  LISTING --> CORE
  LISTING --> CREDIT
  FED --> CREDIT
  CREDIT --> CORE
  CORE --> COMMON
```

| 模块 | 职责（对应原文模块） |
|---|---|
| `tgg-common` | 领域模型（`UpdateContext`）、异常、工具（`IdHasher`）。**无业务逻辑** |
| `tgg-core` | Webhook 接入、中间件链、命令分发、RBAC、限流、审核流水线、违禁词、反刷屏、准入、教学规则、通知、审计、保留策略、泄露通报（一/三/四/九/十） |
| `tgg-credit` | 三套信用分、规则引擎、Ed25519 处罚令（七） |
| `tgg-federation` | 对等节点广播、入站验签、跨群封禁、申诉（八） |
| `tgg-listing` | 群组收录、商家收录与保证金（五/六） |
| `tgg-admin` | 审批中心 REST API（十一，**无界面**） |
| `tgg-app` | Spring Boot 启动器：把各模块放进组件扫描范围，打成单一 jar |

`tgg-core` 体量最大（140 个类、20 个子包），因为「模块一」本身就是横切的接入与调度层。
业务模块一律不反向依赖。

## 三、一次更新的生命周期

`UpdateDispatcher.dispatch`（`tgg-core/.../dispatch/UpdateDispatcher.java`）是唯一入口，Bot 库的
updateHandler 指向它。**顺序本身是设计**：

```mermaid
flowchart TD
  W["Telegram POST /webhook"] --> SEC{"SecretTokenFilter<br/>常量时间比对 secret"}
  SEC -->|"不符"| R401["401 拒绝"]
  SEC -->|"通过"| D{"按更新类型分流"}
  D -->|"callback_query"| CB["CallbackRouter"]
  D -->|"chat_member"| MJ["MemberJoinRecorder<br/>入群时间采集"]
  D -->|"new_chat_members"| JV["JoinVerificationService"]
  D -->|"message / edited_message / channel_post"| CTX["构造 UpdateContext<br/>只带路由元数据，不带正文"]
  CTX --> MOD["审核：L1 正则 · 违禁词 · 反刷屏 · 教学规则 · 敏感话题<br/>worseOf 取最严重"]
  MOD --> ENF{"命中？"}
  ENF -->|"是"| ACT["ModerationEnforcer<br/>删消息 / 封禁（优先于命令）"]
  ENF -->|"否"| MW["中间件链"]
  MW --> CMD["CommandDispatcher"]
  ACT --> SCRUB["finally：MessageScrubber 清除正文"]
  CMD --> SCRUB
```

### 为什么是这个顺序

1. **审核必须在清除正文之前**——那是正文仍在内存中的唯一时机。审核产出 `ModerationVerdict`（不含原文），
   之后才可以安全地挂到上下文进入后续链路。
2. **清除放在 `finally`**——异常路径才是最容易被日志带出正文的那条。
3. **违规处置优先于命令执行**——否则「既违规又带命令」的消息会先被执行、再被删除，本末倒置。
4. **正文口径两端必须一致**——`MessageScrubber` 把 `text` 与 `caption` 都当正文，审核端也必须都看；
   否则带 caption 的图片消息会因 `getText()` 为 null 被判成「审过且干净」（**假 clean**，比没审更危险）。
   同理覆盖投票（问题/选项/增删事件）、地点（标题/地址）、链接预览 URL。
5. **命令豁免的判据是「真的会执行」，不是「看起来像命令」**——若拿 `message.isCommand()` 当豁免依据，
   任何人发 `/任意词 <违规内容>` 就能绕过审核（命令不执行、违规内容却留下）。故用
   `commandDispatcher.willExecute(ctx)`（已注册 + 有权限 + 群开关允许）。
6. **`edited_message` 与 `channel_post` 必须一起审**——「先发干净内容过审、再编辑成广告」是真实绕过手法。

## 四、中间件链与权限

`MiddlewareChain`（不可变、可并发复用）按注册顺序串行，任一返回 `false` 即中断：

```
AuthenticationMiddleware → GroupConfigMiddleware → RateLimitMiddleware
```

| 中间件 | 职责 |
|---|---|
| `AuthenticationMiddleware` | 填充身份信息 |
| `GroupConfigMiddleware` | 群开关：未 `/enable` 的群一律拒绝（恢复类命令除外） |
| `RateLimitMiddleware` | 三级限流：**用户 / 群 / 全局**各一个令牌桶（`InMemoryRateLimiter`） |

**权限模型**是自研的（**未引 Spring Security**）：`Permission{NONE, BAN_USER, MANAGE_CONFIG, TEACH_RULE}`，
由 `PermissionChecker` 判定，来源为 `RoleSource`（群内管理员经 `TGG_PERMISSION_ADMINS` 配置，
格式 `<chatId>:<userId>[:role]`）。

两个刻意的设计：`TGG_PERMISSION_ADMINS` 为空时**所有管理命令对任何人不可用**（门控只拒不放，启动 WARN）；
`CommandDispatcher` 必须注入真实判定器——单参构造器会落到「恒最小权限」的兜底实现，使门控**完全不生效**。

## 五、数据模型

Schema 由 Flyway 管理（15 个迁移，`ddl-auto: validate`——JPA 只校验不建表）。21 张表按模块归属：

| 归属 | 表 |
|---|---|
| core | `group_configs`、`banned_words`、`moderation_review_queue`、`moderation_rules`、`group_topic_tags`、`sensitive_topic_strikes`、`notification_preferences`、`notification_deferred`、`audit_log`、`data_breach_incident`、`member_join_observations` |
| credit | `credit_scores` |
| federation | `federation_appeals`、`federation_penalties` |
| listing | `listing_groups`、`listing_appeals`、`listing_verification_records`、`merchants`、`merchant_deposits`、`merchant_deposit_records` |

**消息正文零存储**：表内没有承载正文的字段。`audit_log` 在应用层**无删除入口**（刻意，合规留痕）。

## 六、扩展点（接缝）

| 接缝 | 位置 | 现状 |
|---|---|---|
| `ModerationLayer` | `core/moderation/` | L1 正则已实现；`LocalModelLayer`（L2/L4）、`DeepSeekLayer`（L3）是**接入位**，默认关闭 |
| `DepositGateway` | `listing/` | `NoopDepositGateway` —— TON 链上能力**未实现**（模块十二为既定非目标） |
| `TeachGate` | `core/wordfilter/` | 三条教学门槛（无违规 / 无扣分 / 入群时长）已实现，聚合器 `TeachEligibility.composite` |
| `CreditEventSink` | `core/credit/` | 未装配时为 noop，主链路零影响 |
| `ApprovalSource` | `tgg-admin/` | 审批中心目前只聚合复核队列（另三个来源在本项目无数据源） |
| `SubmitterNotifier` | `listing/notify/` | 收录下架通知：有 bot token 走真实投递，否则日志实现 + 启动 WARN |

## 七、已知边界（未实现，非遗漏）

- **无 Web 界面**：模块十一只交付 REST API（原文要求的 Vue 3 前端需新增技术栈，**未拍板**）。
- **模块十二 TON 担保交易整体未实现**：链上不可达（Tolk 合约 / 第三方审计 / OFAC-KYC 均不在范围内）。
- **无分布式协调**：`@Scheduled` 任务在多副本下会重复执行（未引 Redis）。
- **风控类能力未实现**：设备指纹、行为序列、图计算、AI 蜜罐（原文 §15.2 列为远期项）。

> 本地开发资产（`docs/` 目录下的 `KNOWN-ISSUES.md`、`LESSONS.md`、`DEPLOYMENT-VERIFICATION.md` 等）
> **不进 git**——换机器或重新 clone 不会带上它们，详见项目根 `.git/info/exclude`。
