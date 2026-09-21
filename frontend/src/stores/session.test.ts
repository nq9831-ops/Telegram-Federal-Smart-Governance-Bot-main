import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AxiosError } from 'axios'

/**
 * 会话状态：**收到 401 后界面必须立刻回到登录页**。
 *
 * 为什么单列：这是**静默失效**型缺陷——令牌已被拦截器清掉，但 `authenticated` 仍为 true，
 * 界面继续渲染「已登录」视图，用户对着一个永远 401 的列表刷新。此前该路径**零测试覆盖**。
 *
 * ⚠️ `client.ts` 模块顶层读写 `localStorage`，vitest 默认环境是 node——故先 stub 全局、
 * 再 `vi.resetModules()` + 动态 import（静态 import 会被提升到 stub 之前）。
 */

function makeStorage(): Storage {
  const map = new Map<string, string>()
  return {
    getItem: (k: string) => (map.has(k) ? (map.get(k) as string) : null),
    setItem: (k: string, v: string) => void map.set(k, String(v)),
    removeItem: (k: string) => void map.delete(k),
    clear: () => void map.clear(),
    key: (i: number) => Array.from(map.keys())[i] ?? null,
    get length() {
      return map.size
    },
  } as Storage
}

beforeEach(() => {
  vi.stubGlobal('localStorage', makeStorage())
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function loadSession() {
  vi.resetModules()
  const client = await import('../api/client')
  const session = await import('./session')
  return { client, session }
}

describe('会话状态 · 401 失效', () => {
  it('收到 401 后 authenticated 变 false（界面回到登录页）', async () => {
    const { client, session } = await loadSession()
    const s = session.useSession()

    // 先登录成功：适配器回 200 的 LoginResponse
    client.http.defaults.adapter = async (config) => ({
      data: {
        token: 'tok-abc',
        subjectType: 'ADMIN_ACCOUNT',
        subjectId: 1,
        role: 'SUPER_ADMIN',
        permissions: [],
      },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    })
    await s.signIn('root', 'pw')
    expect(s.authenticated, '登录成功后应为已登录').toBe(true)

    // 再令下一次请求 401（真实链路上令牌过期即如此）
    client.http.defaults.adapter = async (config) => {
      const response = { data: {}, status: 401, statusText: '', headers: {}, config }
      throw new AxiosError('Request failed with status code 401', 'ERR_BAD_REQUEST',
        undefined, undefined, response as never)
    }
    await expect(client.fetchStats()).rejects.toBeTruthy()

    expect(s.authenticated, '401 后界面必须回到登录页').toBe(false)
    expect(client.hasCredentials(), '令牌也应被清掉').toBe(false)
  })
})
