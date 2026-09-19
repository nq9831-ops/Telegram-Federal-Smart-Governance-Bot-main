import axios, { type AxiosError } from 'axios'
import type {
  ApprovalItem,
  ApprovalPage,
  ApprovalStats,
  DecideFailure,
  DecideRequest,
  DecideSuccess,
  ReviewStatus,
} from './types'

// ─────────────────────────────────────────────────────────────────────────────
// 凭据（与后端 AdminAuthFilter 的**两层**鉴权一一对应）
//   Authorization: Bearer <token>   证明「够得着后台」（共享密钥，不指向具体的人）
//   X-Operator-Id: <userId>         证明「有权审批」（须落在 TGG_MODERATION_REVIEWERS 内）
//
// ⚠️ token 是共享密钥；放 localStorage 意味着 XSS 可窃取。故本工程刻意**不渲染任何
//    HTML/富文本**（全仓无 v-html），也不做额外的持久化。更强的方案（如每次手输 operator）
//    需要产品方另行决定。
// ─────────────────────────────────────────────────────────────────────────────
const TOKEN_KEY = 'tgg.admin.token'
const OPERATOR_KEY = 'tgg.admin.operator'

let token = localStorage.getItem(TOKEN_KEY) ?? ''
let operatorId = localStorage.getItem(OPERATOR_KEY) ?? ''

export function getToken(): string {
  return token
}

export function getOperatorId(): string {
  return operatorId
}

export function hasCredentials(): boolean {
  return token !== '' && operatorId !== ''
}

export function setCredentials(next: { token: string; operatorId: string }): void {
  token = next.token.trim()
  operatorId = next.operatorId.trim()
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(OPERATOR_KEY, operatorId)
}

export function clearCredentials(): void {
  token = ''
  operatorId = ''
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(OPERATOR_KEY)
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
  if (operatorId !== '') {
    config.headers['X-Operator-Id'] = operatorId
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    // 401 = token 缺失或不符。清掉本地凭据，让界面回到「填写凭据」状态，
    // 而不是让用户对着一个永远失败的列表反复刷新。
    if (error.response?.status === 401) {
      clearCredentials()
    }
    return Promise.reject(error)
  },
)

/** 把 axios 错误转成能直接给运营者看的一句话。 */
export function describeError(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status
    if (status === 401) {
      return '鉴权失败（401）：API 令牌缺失或错误，请重新填写。'
    }
    if (status === 403) {
      return '无权限（403）：当前操作人不在复核人白名单内，或该案件属于你本人。'
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

/** `GET /admin/approvals` —— 待办列表（默认按 硬红线 → 等级 → 先入先审 排序，**排序在后端**）。 */
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

/** `GET /admin/approvals/{id}` —— 详情。**不存在返回 null**（后端给 404，不是 200 空体）。 */
export async function fetchApproval(id: number): Promise<ApprovalItem | null> {
  const response = await http.get<ApprovalItem>(`/admin/approvals/${id}`, {
    validateStatus: (status) => status === 200 || status === 404,
  })
  return response.status === 404 ? null : response.data
}

/**
 * `POST /admin/approvals/{id}/decide` —— 裁决。
 *
 * 后端对失败用的是 **400/403 + `{error}`**（不是 200 + 结果码），所以要显式接管这些状态，
 * 否则 axios 会抛一个没有业务语义的异常。
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
