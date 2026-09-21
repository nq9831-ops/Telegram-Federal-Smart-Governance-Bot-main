/**
 * 与后端 `tgg-admin` 的 DTO 一一对应。
 *
 * ⚠️ 这些**不是"约定"**，而是 Jackson 序列化后的真实键名与取值。改这里之前先改后端（或反之）
 * 并同步另一侧。逐项依据（均为实测，非推测）：
 *   - `ApprovalQueryService` 的 `Item` / `Page` / `Stats`
 *   - `ApprovalController` 的 `DecideRequest` 与 `decide(...)` 的返回结构
 *   - `ApprovalCommandService.Result`
 *   - `RiskLevel` / `ReviewStatus` 枚举
 */

/** 风险等级 —— 后端 `RiskLevel`。⚠️ **只有四级**，没有 CRITICAL。 */
export type RiskLevel = 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH'

/**
 * 登录成功响应 —— 后端 `AdminAuthController.LoginResponse`。
 *
 * `subjectType` 区分主体：`ADMIN_ACCOUNT`（后台账号，role 为 SUPER_ADMIN/OPERATOR）
 * 与 `TG_USER`（Telegram 登录，role 为 null）。`subjectId` 是账号 id 或 TG userId。
 */
export interface SessionInfo {
  token: string
  subjectType: 'ADMIN_ACCOUNT' | 'TG_USER'
  subjectId: number
  role: 'SUPER_ADMIN' | 'OPERATOR' | null
  /** 平台能力名（超管为全枚举）——前端据此做**显示分区**；门禁仍在服务端会话。 */
  permissions: string[]
}

/** 复核状态 —— 后端 `ReviewStatus`。 */
export type ReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** 可提交的裁决结论（`ReviewStatus` 除去初始态；后端 `parseDecision` 也只接受这两个）。 */
export type Decision = 'APPROVED' | 'REJECTED'

/**
 * 待办项 —— `ApprovalQueryService.Item`。
 *
 * ⚠️ `ruleIds` 在后端是 **String**（已拼接），**不是数组**——照抄，别"顺手"改成 `string[]`。
 */
export interface ApprovalItem {
  id: number
  chatId: number | null
  userId: number | null
  ruleIds: string | null
  riskLevel: RiskLevel
  hardLine: boolean
  status: ReviewStatus
  createdAt: string
  decidedAt: string | null
  decidedBy: number | null
  note: string | null
  /** 入队至今的小时数。**后端算好的**，前端不要自己按 `createdAt` 推（时区会打架）。 */
  ageHours: number
  overdue: boolean
  /**
   * 该用户在本群的**累计命中次数**（含已裁决）——复核人据此判断是否惯犯。
   * 按「用户 × 群」聚合（不是全局）；发布者缺失（频道帖）时为 0。
   */
  userHitCount: number
  /** 同上，其中**硬红线**的次数。 */
  userHardLineCount: number
}

/** 一页结果 —— `ApprovalQueryService.Page`。 */
export interface ApprovalPage {
  items: ApprovalItem[]
  total: number
  page: number
  size: number
}

/** 统计 —— `ApprovalQueryService.Stats`。`avgDecisionHours` 在无已裁决项时为 null。 */
export interface ApprovalStats {
  pending: number
  approved: number
  rejected: number
  hardLinePending: number
  overdueRemind: number
  overdueEscalate: number
  avgDecisionHours: number | null
}

/** 裁决请求体 —— `ApprovalController.DecideRequest`。 */
export interface DecideRequest {
  decision: Decision
  reason: string
}

/** 裁决**成功**响应（HTTP 200）—— 后端返回 `Map<String,String>`：`{result, status}`。 */
export interface DecideSuccess {
  result: 'DECIDED' | 'ALREADY_DECIDED'
  status: string
}

/**
 * 裁决**失败**响应（HTTP 400 / 403）—— 同样是 `Map<String,String>`：`{error}`。
 * 400 的 `error` 是 core 的语义校验消息（如备注超长）；403 固定为「审批人不可审批自己的案件」。
 */
export interface DecideFailure {
  error: string
}

// ───────────────────────────── 配置中心（模块十一 扩展）─────────────────────────────

/** 配置语义分类 —— 后端 `ConfigCategory`。决定「能不能写」。 */
export type ConfigCategory = 'BOOTSTRAP' | 'SECRET' | 'ASSEMBLY' | 'RUNTIME'

/**
 * 配置项 —— 后端 `RuntimeConfigService.Resolved`。
 *
 * ⚠️ `effectiveValue` 对 `secret` 项恒为 `***` / `未设置`——**后端已打码**，前端拿不到明文。
 * `restartRequired=true` 表示「改后需重启才生效」（装配开关，或尚未改造为热读取的运行参数）。
 */
export interface ConfigItem {
  key: string
  category: ConfigCategory
  type: string
  secret: boolean
  editable: boolean
  restartRequired: boolean
  effectiveValue: string
  defaultValue: string | null
  source: string
  description: string
}

/**
 * 写权限名单来源 —— `ConfigController.WritePermission`。
 *
 * ⚠️ 默认 `source='reviewers'`（回落复核人名单）意味着「能审批的人也能改配置」。界面上必须显式标注，
 * 否则运维会以为两者早已分离。
 */
export interface WritePermission {
  source: 'explicit' | 'reviewers'
  count: number
}

/**
 * 账号视图 —— 后端 `AccountAdminService.AccountView`。
 *
 * `role`：SUPER_ADMIN / OPERATOR；`status`：ACTIVE / DISABLED；
 * `permissions`：能力点名数组（超管返回全部，仅用于显示——超管天然全权）。
 */
export interface AccountView {
  id: number
  username: string
  role: 'SUPER_ADMIN' | 'OPERATOR'
  status: 'ACTIVE' | 'DISABLED'
  permissions: string[]
}

// ───────────────────────────── 我的收录 / 审计（模块十一 · 数据范围 + 护栏）─────────────────────────────

/**
 * 「我提交的收录」一行 —— 后端 `MyContentController.toView`。
 *
 * 数据范围 OWN：后端**在查询条件里**过滤（`findBySubmitterUserIdOrderByIdDesc`），
 * 返回的行只含当前主体自己提交的；后台账号（超管/操作员）没有「自己提交的收录」，恒返回空数组。
 */
export interface MyListing {
  id: number
  chatId: number
  title: string
  status: string
  createdAt: string
}

/**
 * 审计行 —— 后端 `AuditViewController.toView`。
 *
 * ⚠️ `actorType` 与 `actorId` 是**一对**，不能只按 id 判主体（后台账号 id 与 TG userId 数值空间重叠）。
 * `detail` 由后端写入，**不含消息正文**（隐私红线）。
 */
export interface AuditEntry {
  id: number
  actorType: 'TG_USER' | 'ADMIN_ACCOUNT' | null
  actorId: number | null
  action: string
  target: string | null
  caseId: number | null
  outcome: string | null
  detail: string | null
  occurredAt: string
}
