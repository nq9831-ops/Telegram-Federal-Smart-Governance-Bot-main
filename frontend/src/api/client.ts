import axios, { type AxiosError } from 'axios'
import type {
  AccountView,
  ApprovalPage,
  ApprovalStats,
  AuditEntry,
  ConfigItem,
  CreditEvent,
  CreditPage,
  CreditScore,
  CreditSubjectType,
  DecideFailure,
  DecideRequest,
  DecideSuccess,
  MyListing,
  ReviewStatus,
  SessionInfo,
  WritePermission,
} from './types'

// ─────────────────────────────────────────────────────────────────────────────
// 凭据（与后端 AdminSessionFilter 的会话鉴权一一对应）
//   Authorization: Bearer <会话令牌>   由 POST /admin/auth/login 签发
//
// ⚠️ 会话令牌放 localStorage 意味着 XSS 可窃取。故本工程刻意**不渲染任何 HTML/富文本**
//    （全仓无 v-html）。会话由服务端持有、可即时吊销——登出/停用立即失效（相对 JWT 的价值所在）。
// ─────────────────────────────────────────────────────────────────────────────
const TOKEN_KEY = 'tgg.admin.token'

let token = localStorage.getItem(TOKEN_KEY) ?? ''

export function getToken(): string {
  return token
}

export function hasCredentials(): boolean {
  return token !== ''
}

export function clearCredentials(): void {
  token = ''
  localStorage.removeItem(TOKEN_KEY)
}

/**
 * 「会话失效（401）」回调接缝。
 *
 * 拦截器在 401 时会清掉本地令牌；但「界面回到登录页」还需把响应式的会话状态也置为未登录，
 * 而该状态在 `stores/session.ts`。client **不能**反向 import store（`session` 已 import `client`，
 * 会形成模块环），故由 `session` 在初始化时把回调注册进来——依赖方向保持单向。
 */
type UnauthorizedHandler = () => void
let onUnauthorized: UnauthorizedHandler | null = null

export function setOnUnauthorized(handler: UnauthorizedHandler | null): void {
  onUnauthorized = handler
}

// ─────────────────────────────────────────────────────────────────────────────
// HTTP 客户端
// baseURL 留空：开发走 vite 代理的 /admin，生产走反向代理的 /admin（路径一致，见 vite.config.ts）
// ─────────────────────────────────────────────────────────────────────────────
export const http = axios.create({ timeout: 15_000 })

http.interceptors.request.use((config) => {
  if (token !== '') {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    // 401 = 会话缺失/失效。清掉本地令牌，并通知会话 store 把界面切回登录页。
    if (error.response?.status === 401) {
      clearCredentials()
      onUnauthorized?.()
    }
    return Promise.reject(error)
  },
)

// ───────────────────────────── 认证（模块十一 · 账号体系）─────────────────────────────

/**
 * `POST /admin/auth/login` —— 账号 + 密码登录，成功即持有会话令牌。
 *
 * 后端对失败用 **401 + {error}**；这里显式接管该状态，把业务错误抛出（且不写凭据）。
 */
export async function login(username: string, password: string): Promise<SessionInfo> {
  const response = await http.post<SessionInfo>('/admin/auth/login', { username, password }, {
    validateStatus: (status) => status === 200 || status === 401,
  })
  if (response.status !== 200) {
    throw new Error((response.data as { error?: string })?.error ?? '登录名或密码错误')
  }
  token = response.data.token
  localStorage.setItem(TOKEN_KEY, token)
  return response.data
}

/** `POST /admin/auth/logout` —— 吊销当前会话（服务端即时失效）。 */
export async function logout(): Promise<void> {
  try {
    await http.post('/admin/auth/logout')
  } catch {
    // 登出失败不应阻塞前端清理：本地令牌无论如何都清掉
  } finally {
    clearCredentials()
  }
}

/** 审批类 403 的具体成因（不在复核人白名单内 / 不能审自己的案件）。 */
export const FORBIDDEN_APPROVAL = '无权限（403）：你不是超管，也不在复核人白名单内，或者这个案件属于你本人。请让其他复核人处理，或联系超级管理员。'

/** 配置中心 403 的具体成因（不在配置写权限名单内）。 */
export const FORBIDDEN_CONFIG = '无权限（403）：你不是超管，也不在配置写权限名单内。需要权限请联系超级管理员。'

/**
 * 把 axios 错误转成能直接给运营者看的一句话。
 */
export function describeError(error: unknown, forbidden?: string): string {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status
    if (status === 401) {
      return '登录已失效（401）：请重新登录。'
    }
    if (status === 403) {
      return forbidden ?? '无权限（403）：你不在授权名单内。需要权限请联系超级管理员。'
    }
    if (status === undefined) {
      return '无法连接到后端：请确认服务已启动、且反向代理/开发代理配置正确。'
    }
    return `请求失败（HTTP ${status}）。稍后重试；持续失败请联系维护。`
  }
  return error instanceof Error ? error.message : '未知错误。'
}

// ─────────────────────────────────────────────────────────────────────────────
// 端点（与 ApprovalController 的四个映射一一对应）
// ─────────────────────────────────────────────────────────────────────────────

/** `GET /admin/approvals` —— 待办列表。 */
export async function fetchApprovals(params: {
  status?: ReviewStatus
  page?: number
  size?: number
}): Promise<ApprovalPage> {
  const { data } = await http.get<ApprovalPage>('/admin/approvals', { params })
  return data
}

/** `GET /admin/approvals/stats` —— 统计。 */
export async function fetchStats(): Promise<ApprovalStats> {
  const { data } = await http.get<ApprovalStats>('/admin/approvals/stats')
  return data
}

/**
 * `POST /admin/approvals/{id}/decide` —— 裁决。
 *
 * 后端对失败用的是 **400/403 + `{error}`**（不是 200 + 结果码），故显式接管这些状态。
 */
export async function decide(id: number, body: DecideRequest): Promise<DecideSuccess> {
  const response = await http.post<DecideSuccess | DecideFailure>(`/admin/approvals/${id}/decide`, body, {
    validateStatus: (status) => status === 200 || status === 400 || status === 403 || status === 404,
  })
  if (response.status !== 200) {
    const failure = response.data as DecideFailure
    throw new Error(failure?.error ?? `裁决失败（HTTP ${response.status}）`)
  }
  return response.data as DecideSuccess
}

// ───────────────────────────── 配置中心（模块十一 扩展）─────────────────────────────

/** `GET /admin/config` —— 全量配置总览（密钥已由后端打码，前端拿不到明文）。 */
export async function fetchConfig(): Promise<ConfigItem[]> {
  const { data } = await http.get<ConfigItem[]>('/admin/config')
  return data
}

/** `GET /admin/config/permissions` —— 写权限名单来源（只读信息）。 */
export async function fetchPermissions(): Promise<WritePermission> {
  const { data } = await http.get<WritePermission>('/admin/config/permissions')
  return data
}

async function writeConfig(
  send: () => Promise<{ status: number; data: unknown }>,
): Promise<void> {
  const response = await send()
  if (response.status !== 200) {
    const failure = response.data as { error?: string }
    throw new Error(failure?.error ?? `保存失败（HTTP ${response.status}）`)
  }
}

/** `PUT /admin/config/{key}` —— 写覆盖（需配置写权限）。 */
export async function updateConfig(key: string, value: string): Promise<void> {
  const encoded = encodeURIComponent(key)
  await writeConfig(() =>
    http.put(`/admin/config/${encoded}`, { value }, {
      validateStatus: (s) => s === 200 || s === 400 || s === 403 || s === 404 || s === 409,
    }),
  )
}

/** `DELETE /admin/config/{key}` —— 清除覆盖，回落到环境变量/默认。 */
export async function clearConfig(key: string): Promise<void> {
  const encoded = encodeURIComponent(key)
  await writeConfig(() =>
    http.delete(`/admin/config/${encoded}`, {
      validateStatus: (s) => s === 200 || s === 403 || s === 404,
    }),
  )
}

/**
 * `POST /admin/system/restart` —— 触发优雅重启（需配置写权限 + 后端 `restart-enabled=true`）。
 *
 * ⚠️ 后端只负责优雅退出——能否再起来取决于部署侧有无外部监管进程。
 */
export async function restartSystem(): Promise<void> {
  const response = await http.post('/admin/system/restart', null, {
    validateStatus: (s) => s === 202 || s === 403 || s === 409,
  })
  if (response.status !== 202) {
    const failure = response.data as { error?: string }
    throw new Error(failure?.error ?? `重启失败（HTTP ${response.status}）`)
  }
}

// ───────────────────────────── 账号管理（模块十一 · 权限模型，仅超管）─────────────────────────────

/** `GET /admin/accounts` —— 列出全部账号与其能力（仅超管）。 */
export async function fetchAccounts(): Promise<AccountView[]> {
  const { data } = await http.get<AccountView[]>('/admin/accounts')
  return data
}

/** 账号管理写入的失败（400/403 + `{error}`）。 */
async function mutateAccount(
  send: () => Promise<{ status: number; data: unknown }>,
  failureText: string,
): Promise<void> {
  const response = await send()
  if (response.status !== 200) {
    const failure = response.data as { error?: string }
    throw new Error(failure?.error ?? `${failureText}（HTTP ${response.status}）`)
  }
}

/** `POST /admin/accounts` —— 建操作员（role 恒为 OPERATOR）。 */
export async function createAccount(username: string, password: string): Promise<void> {
  await mutateAccount(
    () => http.post('/admin/accounts', { username, password }, {
      validateStatus: (s) => s === 200 || s === 400 || s === 403,
    }),
    '创建失败',
  )
}

/** `PUT /admin/accounts/{id}/permissions` —— 设置能力清单。 */
export async function setAccountPermissions(id: number, permissions: string[]): Promise<void> {
  await mutateAccount(
    () => http.put(`/admin/accounts/${id}/permissions`, { permissions }, {
      validateStatus: (s) => s === 200 || s === 400 || s === 403,
    }),
    '设置权限失败',
  )
}

/** `PUT /admin/accounts/{id}/status` —— 停用 / 启用。 */
export async function setAccountStatus(id: number, status: 'ACTIVE' | 'DISABLED'): Promise<void> {
  await mutateAccount(
    () => http.put(`/admin/accounts/${id}/status`, { status }, {
      validateStatus: (s) => s === 200 || s === 400 || s === 403,
    }),
    '变更状态失败',
  )
}

/** `PUT /admin/accounts/{id}/password` —— 重置密码（改后强制下线）。 */
export async function resetAccountPassword(id: number, password: string): Promise<void> {
  await mutateAccount(
    () => http.put(`/admin/accounts/${id}/password`, { password }, {
      validateStatus: (s) => s === 200 || s === 400 || s === 403,
    }),
    '重置密码失败',
  )
}

/** `POST /admin/accounts/{id}/revoke-sessions` —— 强制下线。 */
export async function revokeAccountSessions(id: number): Promise<void> {
  await mutateAccount(
    () => http.post(`/admin/accounts/${id}/revoke-sessions`, null, {
      validateStatus: (s) => s === 200 || s === 400 || s === 403,
    }),
    '强制下线失败',
  )
}

/** 平台能力点（后端 `PlatformPermission`）。 */
export const ALL_PERMISSIONS = [
  'REVIEW_DECIDE',
  'CONFIG_WRITE',
  'FEDERATION_ADMIN',
  'MERCHANT_REVIEW',
  'SYSTEM_RESTART',
  'AUDIT_READ',
  'CREDIT_READ',
] as const

/** 能力点的中文说明——界面呈现用，避免裸显后端英文枚举。 */
export const PERMISSION_LABEL: Record<string, string> = {
  REVIEW_DECIDE: '审批裁决',
  CONFIG_WRITE: '配置写入',
  FEDERATION_ADMIN: '联邦管理',
  MERCHANT_REVIEW: '商家复核',
  SYSTEM_RESTART: '系统重启',
  AUDIT_READ: '审计查看',
  CREDIT_READ: '信用查看',
}

// ───────────────────────────── Telegram 登录（模块十一 · TG 用户登录）─────────────────────────────

/** `GET /admin/auth/login-config` —— 登录页配置（bot username）。公开端点，无需会话。 */
export async function fetchLoginConfig(): Promise<{ telegramBotUsername: string }> {
  const { data } = await http.get<{ telegramBotUsername: string }>('/admin/auth/login-config')
  return data
}

/**
 * `POST /admin/auth/telegram` —— Telegram Login Widget 回调。
 *
 * widget 回调里的字段**一律不可信**——服务端会用 bot token 重算 HMAC 验签；
 * 这里只负责把原始字段交上去、并在成功时保存会话令牌。
 */
export async function telegramLogin(data: Record<string, string>): Promise<SessionInfo> {
  const response = await http.post<SessionInfo>('/admin/auth/telegram', data, {
    validateStatus: (s) => s === 200 || s === 401 || s === 503,
  })
  if (response.status !== 200) {
    throw new Error((response.data as { error?: string })?.error ?? 'Telegram 登录失败')
  }
  token = response.data.token
  localStorage.setItem(TOKEN_KEY, token)
  return response.data
}

// ───────────────────────────── 我的收录 / 审计（模块十一 · 数据范围 + 护栏）─────────────────────────────

/**
 * `GET /admin/my/listings` —— 「我提交的收录」（数据范围 OWN）。
 *
 * 过滤在**后端查询条件**里（`findBySubmitterUserIdOrderByIdDesc`），前端不再过滤；
 * 后台账号没有「自己提交的收录」，恒返回空数组。
 */
export async function fetchMyListings(): Promise<MyListing[]> {
  const { data } = await http.get<MyListing[]>('/admin/my/listings')
  return data
}

/** 审计单次上限（与后端 `AuditViewController.MAX_LIMIT` 一致）。 */
export const AUDIT_MAX_LIMIT = 200

/**
 * `GET /admin/audit/recent?limit=N` —— 最近若干条审计（倒序，只读）。
 *
 * 超管天然可读；其余主体需持 `AUDIT_READ`，否则后端 403（`{error}`）——由调用方按 403 渲染无权限态。
 */
export async function fetchAuditRecent(limit = 100): Promise<AuditEntry[]> {
  const { data } = await http.get<AuditEntry[]>('/admin/audit/recent', { params: { limit } })
  return data
}

// ───────────────────── 信用账本 / 流水（gap-01 · 只读可见面）─────────────────────

/** 信用单页上限（与后端 `CreditQueryController.MAX_PAGE_SIZE` 一致）。 */
export const CREDIT_MAX_PAGE_SIZE = 200

/** 信用查询的过滤 + 分页入参（与后端 `@RequestParam` 一一对应）。 */
export interface CreditQuery {
  subjectType?: CreditSubjectType
  subjectId?: number
  page?: number
  size?: number
}

/** 只把「有值」的过滤条件放进 query——空串/空值时后端按「不过滤」处理，不发无意义的参数。 */
function creditParams(query: CreditQuery): Record<string, string | number> {
  const params: Record<string, string | number> = {}
  if (query.subjectType !== undefined) {
    params.subjectType = query.subjectType
  }
  if (query.subjectId !== undefined) {
    params.subjectId = query.subjectId
  }
  if (query.page !== undefined) {
    params.page = query.page
  }
  if (query.size !== undefined) {
    params.size = query.size
  }
  return params
}

/**
 * `GET /admin/credit/events` —— 信用流水（倒序，只读）。
 *
 * 超管天然可读；其余主体需持 `CREDIT_READ`，否则后端 403（`{error}`）——由调用方按 403 渲染无权限态。
 */
export async function fetchCreditEvents(query: CreditQuery = {}): Promise<CreditPage<CreditEvent>> {
  const { data } = await http.get<CreditPage<CreditEvent>>('/admin/credit/events', {
    params: creditParams(query),
  })
  return data
}

/** `GET /admin/credit/scores` —— 信用账本（倒序，只读）。权限同流水。 */
export async function fetchCreditScores(query: CreditQuery = {}): Promise<CreditPage<CreditScore>> {
  const { data } = await http.get<CreditPage<CreditScore>>('/admin/credit/scores', {
    params: creditParams(query),
  })
  return data
}

// ─────────────────────  Telegram Mini App（模块十二 · 前端壳，gap-ESC-04）─────────────────────

/**
 * `POST /admin/auth/miniapp` —— Mini App `initData` 换会话令牌。
 *
 * <p>与 Widget 的 `/admin/auth/telegram` 并排：验签器不同（Mini App 的 `secret_key` 是
 * `HMAC_SHA256(key="WebAppData", message=bot_token)`，与 Widget 的 `SHA256(bot_token)`
 * **相反、不可混用**），但签发出来的都是同一套 `AdminSessionFilter` 会话，
 * 故响应体就是既有的 `SessionInfo`（不新造第二种会话模型）。
 *
 * <p>widget / initData 里的字段**一律不可信**——验签在服务端完成，前端只负责把原始串交上去。
 *
 * <p>⚠️ 端点是后端 initData 验签（gap-ESC-03）的落点，须同时进 `AdminSessionFilter` 放行名单
 * （登录本身无需会话）；本批只落前端壳，**未在真实环境验证**（无真机 initData、无真实 WebView）。
 */
export const MINIAPP_LOGIN_PATH = '/admin/auth/miniapp'

/** 宿主注入的 Mini App 桥——只用到 `initData` 一个字段，故**不引** `@telegram-apps/sdk`（报告 §4.2 N1）。 */
interface TelegramWebAppBridge {
  initData?: string
}

function telegramWebApp(): TelegramWebAppBridge | null {
  const w = window as unknown as { Telegram?: { WebApp?: TelegramWebAppBridge } }
  return w.Telegram?.WebApp ?? null
}

/**
 * 从 URL 里取 Telegram 的启动参数。
 *
 * <p>Telegram 打开 Mini App 时会把启动参数写进 URL 片段（`tgWebAppData` 等，值经 URL 编码）；
 * 页面若未引入 `telegram-web-app.js`，这就是**唯一**的 initData 来源。
 */
function launchParam(name: string): string {
  const sources = [
    window.location.hash.replace(/^#/, ''),
    window.location.search.replace(/^\?/, ''),
  ]
  for (const raw of sources) {
    if (raw === '') {
      continue
    }
    for (const pair of raw.split('&')) {
      const eq = pair.indexOf('=')
      if (eq <= 0) {
        continue
      }
      if (decodeURIComponent(pair.slice(0, eq)) === name) {
        return decodeURIComponent(pair.slice(eq + 1))
      }
    }
  }
  return ''
}

/**
 * 原始 `initData` query 串——**未验签，不可信**。
 *
 * <p>两个来源：宿主对象 `window.Telegram.WebApp.initData`（页面引入了 `telegram-web-app.js` 时），
 * 或启动参数 `tgWebAppData`（Telegram 打开 Mini App 时写进 URL）。两者都没有则返回空串
 * ——含义是「不在 Mini App 容器内」（在浏览器里直接打开控制台）。
 */
export function readMiniAppInitData(): string {
  const injected = telegramWebApp()?.initData ?? ''
  return injected !== '' ? injected : launchParam('tgWebAppData')
}

/** 是否运行在 Telegram Mini App 容器内——判据只有一条：拿不拿得到 initData。 */
export function isMiniAppEnvironment(): boolean {
  return readMiniAppInitData() !== ''
}

/**
 * Mini App 登录：把原始 `initData` 交给服务端验签，成功即持有会话令牌。
 *
 * <p>失败面比账号密码登录多一档：容器外打开 / 非白名单用户（403）——错误文案由后端 `{error}` 给出，
 * 前端不猜成因。
 */
export async function miniAppLogin(initData: string): Promise<SessionInfo> {
  const response = await http.post<SessionInfo>(MINIAPP_LOGIN_PATH, { initData }, {
    validateStatus: (s) => s === 200 || s === 401 || s === 403 || s === 503,
  })
  if (response.status !== 200) {
    throw new Error(
      (response.data as { error?: string })?.error ?? 'Mini App 登录失败：initData 未通过服务端校验。',
    )
  }
  token = response.data.token
  localStorage.setItem(TOKEN_KEY, token)
  return response.data
}
