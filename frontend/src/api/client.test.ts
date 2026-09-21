import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import axios, { AxiosError } from 'axios'

/**
 * `client.ts` 的纯逻辑测试。
 *
 * 为什么这几条值得测：它们全是**静默失效**型的错误——
 * 少补一个鉴权头、401 后不清理令牌、错误文案指向错误的原因，
 * 都不会抛异常、不会崩，只会让运营者「以为后端坏了」或「反复对着永远失败的列表刷新」。
 *
 * ⚠️ `client.ts` 在**模块顶层**就读写 `localStorage`（`let token = localStorage.getItem(...)`），
 * 而 vitest 默认环境是 node（无 `localStorage`）——所以每个用例都要
 * **先 stub 全局、再动态 import**（静态 import 会被提升到 stub 之前，直接 ReferenceError）。
 */

/** 最小 localStorage 替身。 */
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

let storage: Storage

/** stub localStorage → 重置模块注册表 → 重新加载 client（使模块顶层读到当前 storage）。 */
async function loadClient() {
  vi.resetModules()
  return await import('./client')
}

/** 大小写不敏感地取一个头——axios 在适配器里给的是 `AxiosHeaders` 实例。 */
function headerValue(headers: unknown, name: string): unknown {
  const h = headers as { get?: (n: string) => unknown } & Record<string, unknown>
  return typeof h?.get === 'function' ? h.get(name) : h?.[name]
}

/** 造一个「像内置适配器那样 reject」的适配器（驱动响应拦截器的 error 分支）。 */
function adapterRejecting(status: number) {
  return async (config: unknown) => {
    const response = { data: {}, status, statusText: '', headers: {}, config }
    throw new AxiosError(
      `Request failed with status code ${status}`,
      'ERR_BAD_REQUEST',
      undefined,
      undefined,
      response as never,
    )
  }
}

beforeEach(() => {
  storage = makeStorage()
  vi.stubGlobal('localStorage', storage)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

// ─────────────────────────────────────────────────────────────────────────────
describe('凭据存取（会话令牌）', () => {
  it('未登录：空 token，hasCredentials 为 false', async () => {
    const c = await loadClient()
    expect(c.getToken()).toBe('')
    expect(c.hasCredentials()).toBe(false)
  })

  it('localStorage 里有令牌时，模块加载即恢复登录态（刷新不掉线）', async () => {
    storage.setItem('tgg.admin.token', 'persisted')

    const c = await loadClient()
    expect(c.getToken()).toBe('persisted')
    expect(c.hasCredentials()).toBe(true)
  })

  it('clearCredentials 同时清内存与 localStorage', async () => {
    storage.setItem('tgg.admin.token', 'persisted')
    const c = await loadClient()

    c.clearCredentials()

    expect(c.hasCredentials()).toBe(false)
    expect(c.getToken()).toBe('')
    expect(storage.getItem('tgg.admin.token')).toBe(null)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
describe('请求拦截器：补会话令牌', () => {
  it('有令牌时补 Authorization: Bearer，且**不再**补已废弃的 X-Operator-Id', async () => {
    storage.setItem('tgg.admin.token', 'tok-123')
    const c = await loadClient()

    let seen: { headers: unknown } | null = null
    c.http.defaults.adapter = async (config) => {
      seen = config
      return { data: {}, status: 200, statusText: 'OK', headers: {}, config }
    }
    await c.http.get('/admin/approvals')

    expect(headerValue(seen!.headers, 'Authorization')).toBe('Bearer tok-123')
    expect(headerValue(seen!.headers, 'X-Operator-Id')).toBeUndefined()
  })

  it('无令牌时不补头（且不补空值）', async () => {
    const c = await loadClient()

    let seen: { headers: unknown } | null = null
    c.http.defaults.adapter = async (config) => {
      seen = config
      return { data: {}, status: 200, statusText: 'OK', headers: {}, config }
    }
    await c.http.get('/admin/approvals')

    expect(headerValue(seen!.headers, 'Authorization')).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
describe('响应拦截器：401 清令牌', () => {
  it('401 时清掉本地令牌（让界面回到登录页）', async () => {
    storage.setItem('tgg.admin.token', 'tok-123')
    const c = await loadClient()
    c.http.defaults.adapter = adapterRejecting(401)

    await expect(c.http.get('/admin/approvals')).rejects.toBeTruthy()
    expect(c.hasCredentials()).toBe(false)
    expect(storage.getItem('tgg.admin.token')).toBe(null)
  })

  it('非 401（如 500）不清令牌——那是后端故障，不是鉴权问题', async () => {
    storage.setItem('tgg.admin.token', 'tok-123')
    const c = await loadClient()
    c.http.defaults.adapter = adapterRejecting(500)

    await expect(c.http.get('/admin/approvals')).rejects.toBeTruthy()
    expect(c.hasCredentials()).toBe(true)
    expect(storage.getItem('tgg.admin.token')).toBe('tok-123')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
describe('login', () => {
  it('成功：写入令牌并回传主体', async () => {
    const c = await loadClient()
    c.http.defaults.adapter = async (config) => ({
      data: { token: 'tok', subjectType: 'ADMIN_ACCOUNT', subjectId: 7, role: 'SUPER_ADMIN' },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    })

    const info = await c.login('root', 'pw')

    expect(info.token).toBe('tok')
    expect(info.role).toBe('SUPER_ADMIN')
    expect(c.getToken()).toBe('tok')
    expect(c.hasCredentials()).toBe(true)
    expect(storage.getItem('tgg.admin.token')).toBe('tok')
  })

  it('401：抛业务错误且**不**写令牌', async () => {
    const c = await loadClient()
    c.http.defaults.adapter = async (config) => ({
      data: { error: '登录名或密码错误' }, status: 401, statusText: '', headers: {}, config,
    })

    await expect(c.login('root', 'bad')).rejects.toThrow('登录名或密码错误')
    expect(c.hasCredentials()).toBe(false)
    expect(storage.getItem('tgg.admin.token')).toBe(null)
  })
})

describe('logout', () => {
  it('无论后端成败都清掉本地令牌', async () => {
    storage.setItem('tgg.admin.token', 'tok-123')
    const c = await loadClient()
    c.http.defaults.adapter = adapterRejecting(500)

    await c.logout()

    expect(c.hasCredentials()).toBe(false)
    expect(storage.getItem('tgg.admin.token')).toBe(null)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
describe('describeError：把错误翻成运营者能懂的一句话', () => {
  // 文案逐字取自 client.ts——改文案必须先改测试，反之亦然。
  function axiosError(status: number): AxiosError {
    return new AxiosError('boom', 'ERR_BAD_REQUEST', undefined, undefined, {
      status,
      statusText: '',
      headers: {},
      config: {} as never,
      data: {},
    })
  }

  it('401 → 登录失效文案', async () => {
    const c = await loadClient()
    expect(c.describeError(axiosError(401))).toBe('登录已失效（401）：请重新登录。')
  })

  it('403 → 默认中性文案（不绑死某个功能区）', async () => {
    const c = await loadClient()
    expect(c.describeError(axiosError(403))).toBe('无权限（403）：你不在授权名单内。需要权限请联系超级管理员。')
  })

  it('403 → 传入 forbidden 时用该功能区的具体成因（审批 / 配置中心各不同）', async () => {
    const c = await loadClient()
    expect(c.describeError(axiosError(403), c.FORBIDDEN_APPROVAL))
      .toBe('无权限（403）：你不是超管，也不在复核人白名单内，或者这个案件属于你本人。请让其他复核人处理，或联系超级管理员。')
    expect(c.describeError(axiosError(403), c.FORBIDDEN_CONFIG))
      .toBe('无权限（403）：你不是超管，也不在配置写权限名单内。需要权限请联系超级管理员。')
  })

  it('无响应（连不上后端）→ 连接文案', async () => {
    const c = await loadClient()
    const err = new AxiosError('Network Error', 'ERR_NETWORK')
    expect(c.describeError(err)).toBe('无法连接到后端：请确认服务已启动、且反向代理/开发代理配置正确。')
  })

  it('其他状态码 → 带 HTTP 码的兜底文案', async () => {
    const c = await loadClient()
    expect(c.describeError(axiosError(500))).toBe('请求失败（HTTP 500）。稍后重试；持续失败请联系维护。')
  })

  it('普通 Error → 透传 message', async () => {
    const c = await loadClient()
    expect(c.describeError(new Error('自定义失败'))).toBe('自定义失败')
  })

  it('非 Error 值 → 未知错误', async () => {
    const c = await loadClient()
    expect(c.describeError('裸字符串')).toBe('未知错误。')
    expect(c.describeError(undefined)).toBe('未知错误。')
  })
})

// 让 `axios` 的 import 被使用（避免 TS 未使用告警）。
void axios
