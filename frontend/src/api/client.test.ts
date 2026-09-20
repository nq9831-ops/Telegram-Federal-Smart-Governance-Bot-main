import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import axios, { AxiosError } from 'axios'

/**
 * `client.ts` 的纯逻辑测试。
 *
 * 为什么这几条值得测：它们全是**静默失效**型的错误——
 * 少补一个鉴权头、401 后不清理凭据、错误文案指向错误的原因，
 * 都不会抛异常、不会崩，只会让运营者「以为后端坏了」或「反复对着永远失败的列表刷新」。
 *
 * ⚠️ `client.ts` 在**模块顶层**就读写 `localStorage`（`let token = localStorage.getItem(...)`），
 * 而 vitest 默认环境是 node（无 `localStorage`）——所以每个用例都要
 * **先 stub 全局、再动态 import**（静态 import 会被提升到 stub 之前，直接 ReferenceError）。
 */

/** 最小 localStorage 替身：足够跑通读/写/删，且可断言落盘值。 */
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

/**
 * 造一个「适配器」，对给定状态码**像内置适配器那样 reject**。
 *
 * ⚠️ 不能直接 resolve 一个 401/500 响应来驱动响应拦截器：`validateStatus` 的校验（`settle`）
 * 是**内置适配器（xhr/http）自己**调用的，axios 核心的 `dispatchRequest` 只做
 * `adapter(config).then(...)`——自定义适配器 resolve 一个非 2xx 响应，核心**不会**代为 reject，
 * 于是响应拦截器的 error 分支根本不会被走到（本用例第一版就是这么假绿的）。
 * 依据：`node_modules/axios/dist/node/axios.cjs` 的 `dispatchRequest`（约 6157 行）与 `settle`。
 */
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
describe('凭据存取', () => {
  it('未存过凭据时：空 token / 空 operator，hasCredentials 为 false', async () => {
    const c = await loadClient()
    expect(c.getToken()).toBe('')
    expect(c.getOperatorId()).toBe('')
    expect(c.hasCredentials()).toBe(false)
  })

  it('setCredentials 写入时 trim，并落进 localStorage', async () => {
    const c = await loadClient()
    c.setCredentials({ token: '  tok-123  ', operatorId: ' 42 ' })

    expect(c.getToken()).toBe('tok-123')
    expect(c.getOperatorId()).toBe('42')
    expect(c.hasCredentials()).toBe(true)
    expect(storage.getItem('tgg.admin.token')).toBe('tok-123')
    expect(storage.getItem('tgg.admin.operator')).toBe('42')
  })

  it('只填一半（缺 operator）不算已登录——两层鉴权缺一不可', async () => {
    const c = await loadClient()
    c.setCredentials({ token: 'tok-123', operatorId: '' })
    expect(c.hasCredentials()).toBe(false)
  })

  it('clearCredentials 同时清内存与 localStorage', async () => {
    const c = await loadClient()
    c.setCredentials({ token: 'tok-123', operatorId: '42' })
    c.clearCredentials()

    expect(c.hasCredentials()).toBe(false)
    expect(c.getToken()).toBe('')
    expect(storage.getItem('tgg.admin.token')).toBe(null)
    expect(storage.getItem('tgg.admin.operator')).toBe(null)
  })

  it('localStorage 里已有值时，模块加载即恢复登录态（刷新不掉线）', async () => {
    storage.setItem('tgg.admin.token', 'persisted')
    storage.setItem('tgg.admin.operator', '7')

    const c = await loadClient()
    expect(c.getToken()).toBe('persisted')
    expect(c.getOperatorId()).toBe('7')
    expect(c.hasCredentials()).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
describe('请求拦截器：补两层鉴权头', () => {
  it('有凭据时补 Authorization 与 X-Operator-Id', async () => {
    const c = await loadClient()
    c.setCredentials({ token: 'tok-123', operatorId: '42' })

    let seen: { headers: unknown } | null = null
    c.http.defaults.adapter = async (config) => {
      seen = config
      return { data: {}, status: 200, statusText: 'OK', headers: {}, config }
    }
    await c.http.get('/admin/approvals')

    expect(headerValue(seen!.headers, 'Authorization')).toBe('Bearer tok-123')
    expect(headerValue(seen!.headers, 'X-Operator-Id')).toBe('42')
  })

  it('无凭据时不补头（且不补空值）', async () => {
    const c = await loadClient()

    let seen: { headers: unknown } | null = null
    c.http.defaults.adapter = async (config) => {
      seen = config
      return { data: {}, status: 200, statusText: 'OK', headers: {}, config }
    }
    await c.http.get('/admin/approvals')

    expect(headerValue(seen!.headers, 'Authorization')).toBeUndefined()
    expect(headerValue(seen!.headers, 'X-Operator-Id')).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
describe('响应拦截器：401 清凭据', () => {
  it('401 时清掉本地凭据（让界面回到「填写凭据」，而不是对着失败列表刷）', async () => {
    const c = await loadClient()
    c.setCredentials({ token: 'tok-123', operatorId: '42' })
    c.http.defaults.adapter = adapterRejecting(401)

    await expect(c.http.get('/admin/approvals')).rejects.toBeTruthy()
    expect(c.hasCredentials()).toBe(false)
    expect(storage.getItem('tgg.admin.token')).toBe(null)
  })

  it('非 401（如 500）不清凭据——那是后端故障，不是鉴权问题', async () => {
    const c = await loadClient()
    c.setCredentials({ token: 'tok-123', operatorId: '42' })
    c.http.defaults.adapter = adapterRejecting(500)

    await expect(c.http.get('/admin/approvals')).rejects.toBeTruthy()
    expect(c.hasCredentials()).toBe(true)
    expect(storage.getItem('tgg.admin.token')).toBe('tok-123')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
describe('describeError：把错误翻成运营者能懂的一句话', () => {
  // 文案逐字取自 client.ts——改文案必须先改测试，反之亦然。
  it('401 → 鉴权失败文案', async () => {
    const c = await loadClient()
    const err = new AxiosError('boom', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 401,
      statusText: '',
      headers: {},
      config: {} as never,
      data: {},
    })
    expect(c.describeError(err)).toBe('鉴权失败（401）：API 令牌缺失或错误，请重新填写。')
  })

  it('403 → 默认中性文案（不绑死某个功能区）', async () => {
    const c = await loadClient()
    const err = new AxiosError('boom', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 403,
      statusText: '',
      headers: {},
      config: {} as never,
      data: {},
    })
    expect(c.describeError(err)).toBe('无权限（403）：当前操作人不在授权名单内。')
  })

  it('403 → 传入 forbidden 时用该功能区的具体成因（审批 / 配置中心各不同）', async () => {
    const c = await loadClient()
    const err = new AxiosError('boom', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 403,
      statusText: '',
      headers: {},
      config: {} as never,
      data: {},
    })
    expect(c.describeError(err, c.FORBIDDEN_APPROVAL))
      .toBe('无权限（403）：当前操作人不在复核人白名单内，或该案件属于你本人。')
    expect(c.describeError(err, c.FORBIDDEN_CONFIG))
      .toBe('无权限（403）：当前操作人不在配置写权限名单内。')
  })

  it('无响应（连不上后端）→ 连接文案', async () => {
    const c = await loadClient()
    const err = new AxiosError('Network Error', 'ERR_NETWORK')
    expect(c.describeError(err)).toBe('无法连接到后端：请确认服务已启动、且反向代理/开发代理配置正确。')
  })

  it('其他状态码 → 带 HTTP 码的兜底文案', async () => {
    const c = await loadClient()
    const err = new AxiosError('boom', 'ERR_BAD_RESPONSE', undefined, undefined, {
      status: 500,
      statusText: '',
      headers: {},
      config: {} as never,
      data: {},
    })
    expect(c.describeError(err)).toBe('请求失败（HTTP 500）。')
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

// 让 `axios` 与 `AxiosError` 的 import 都被使用（避免 TS 未使用告警）。
void axios
