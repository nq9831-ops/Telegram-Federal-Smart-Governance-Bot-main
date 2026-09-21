import axios, { type AxiosError } from 'axios'
import type {
  ApprovalPage,
  ApprovalStats,
  ConfigItem,
  DecideFailure,
  DecideRequest,
  DecideSuccess,
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
    // 401 = 会话缺失/失效。清掉本地令牌，让界面回到「登录」状态。
    if (error.response?.status === 401) {
      clearCredentials()
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
export const FORBIDDEN_APPROVAL = '无权限（403）：当前主体不是超管，且不在复核人白名单内，或该案件属于你本人。'

/** 配置中心 403 的具体成因（不在配置写权限名单内）。 */
export const FORBIDDEN_CONFIG = '无权限（403）：当前主体不是超管，且不在配置写权限名单内。'

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
      return forbidden ?? '无权限（403）：当前主体不在授权名单内。'
    }
    if (status === undefined) {
      return '无法连接到后端：请确认服务已启动、且反向代理/开发代理配置正确。'
    }
    return `请求失败（HTTP ${status}）。`
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
