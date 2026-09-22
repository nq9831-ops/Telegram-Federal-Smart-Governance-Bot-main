import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  hasCredentials,
  login as apiLogin,
  logout as apiLogout,
  setOnUnauthorized,
  telegramLogin as apiTelegramLogin,
} from '../api/client'

/**
 * 会话状态：后端 `AdminSessionFilter` 要求的**会话令牌**（`Authorization: Bearer <令牌>`）。
 *
 * 用 pinia 而不是模块级变量，是为了让「未登录 ⇄ 已登录」的界面切换是**响应式**的——
 * 401 之后拦截器会清掉令牌，界面应当立刻回到登录页。
 *
 * 身份（主体类型 / id / 角色）来自登录响应，前端只用于显示与分区，**不作安全判定**
 * ——真正的门禁在服务端会话。
 */
export const useSession = defineStore('session', () => {
  const authenticated = ref(hasCredentials())
  const subjectType = ref<'ADMIN_ACCOUNT' | 'TG_USER' | ''>('')
  const subjectId = ref<number | null>(null)
  const role = ref<'SUPER_ADMIN' | 'OPERATOR' | null>(null)
  const permissions = ref<string[]>([])

  // 会话失效（401）时立刻回到登录页：client 不能反向 import 本 store（会成环），
  // 故在此把回调交给它。拦截器清掉令牌后调用，界面据 authenticated 切回登录卡。
  setOnUnauthorized(() => {
    subjectType.value = ''
    subjectId.value = null
    role.value = null
    permissions.value = []
    authenticated.value = false
  })

  /**
   * 是否显示「审计」入口——**显示分区，不是安全边界**：真正放行/拒绝在服务端
   * （`AuditViewController` 校验超管或 `AUDIT_READ`）。权限未知时一律不显示（保守）。
   */
  const canReadAudit = computed(() =>
    role.value === 'SUPER_ADMIN' || permissions.value.includes('AUDIT_READ'),
  )

  /**
   * 是否显示「信用」入口——**显示分区，不是安全边界**：真正放行/拒绝在服务端
   * （`CreditQueryController` 校验超管或 `CREDIT_READ`）。权限未知时一律不显示（保守）。
   */
  const canReadCredit = computed(() =>
    role.value === 'SUPER_ADMIN' || permissions.value.includes('CREDIT_READ'),
  )

  /** 顶部身份行文案。 */
  const operatorLabel = computed(() => {
    if (role.value === 'SUPER_ADMIN') {
      return '超级管理员'
    }
    if (role.value === 'OPERATOR') {
      return `操作员 #${subjectId.value ?? ''}`
    }
    if (subjectType.value === 'TG_USER') {
      return `TG 用户 #${subjectId.value ?? ''}`
    }
    return subjectId.value === null ? '' : `#${subjectId.value}`
  })

  async function signIn(username: string, password: string): Promise<void> {
    const info = await apiLogin(username, password)
    subjectType.value = info.subjectType
    subjectId.value = info.subjectId
    role.value = info.role
    permissions.value = info.permissions ?? []
    authenticated.value = true
  }

  /** Telegram 登录：验签在服务端完成，这里只保存会话与身份。 */
  async function signInWithTelegram(user: Record<string, string>): Promise<void> {
    const info = await apiTelegramLogin(user)
    subjectType.value = info.subjectType
    subjectId.value = info.subjectId
    role.value = info.role
    permissions.value = info.permissions ?? []
    authenticated.value = true
  }

  async function signOut(): Promise<void> {
    await apiLogout()
    subjectType.value = ''
    subjectId.value = null
    role.value = null
    permissions.value = []
    authenticated.value = false
  }

  return {
    authenticated, subjectType, subjectId, role, permissions, operatorLabel, canReadAudit,
    canReadCredit, signIn, signInWithTelegram, signOut,
  }
})
