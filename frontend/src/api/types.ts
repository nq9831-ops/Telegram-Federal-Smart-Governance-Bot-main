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
