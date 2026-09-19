import { defineStore } from 'pinia'
import { ref } from 'vue'
import { clearCredentials, getOperatorId, hasCredentials, setCredentials } from '../api/client'

/**
 * 会话状态：后端 `AdminAuthFilter` 要求的**两层**凭据。
 *
 * 用 pinia 而不是模块级变量，是为了让「未登录 ⇄ 已登录」的界面切换是**响应式**的——
 * 401 之后拦截器会清掉凭据，界面应当立刻回到门禁页，而不是留着空列表让人反复刷新。
 */
export const useSession = defineStore('session', () => {
  const authenticated = ref(hasCredentials())
  const operator = ref(getOperatorId())

  function signIn(next: { token: string; operatorId: string }): void {
    setCredentials(next)
    operator.value = getOperatorId()
    authenticated.value = hasCredentials()
  }

  function signOut(): void {
    clearCredentials()
    operator.value = ''
    authenticated.value = false
  }

  return { authenticated, operator, signIn, signOut }
})
