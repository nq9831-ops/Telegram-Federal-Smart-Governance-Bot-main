// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * Mini App 前端的「取数面」与「换会话」测试（模块十二 · gap-ESC-04）。
 *
 * <p>为什么值得单测这两件：它们全是**静默失效**型的错误——
 * 启动参数解析错一个字符，用户在真机里看到的就是「拿不到 initData」；
 * 换会话时把响应体当令牌写进 localStorage，界面会显示「已登录」但每个请求都 401。
 * 真链 / 真机不可达（报告 §7），故把**可本地判定**的部分（来源优先级 / 解码 / 令牌落地）钉死。
 *
 * <p>⚠️ 未在真实环境验证：无真机 initData、无 Telegram WebView；`POST /admin/auth/miniapp`
 * 是后端 initData 验签（gap-ESC-03）的落点，本批只落前端契约。
 */

/** stub 全局 → 重置模块注册表 → 重新加载 client（模块顶层会读 localStorage）。 */
async function loadClient() {
  vi.resetModules()
  return await import('./client')
}

function setTelegramWebApp(initData: string | null): void {
  const w = window as unknown as { Telegram?: unknown }
  if (initData === null) {
    delete w.Telegram
    return
  }
  w.Telegram = { WebApp: { initData } }
}

/** 把启动参数写进 URL（与 Telegram 打开小程序时的形态一致）。 */
function setLaunchParams(fragment: string): void {
  window.history.replaceState(null, '', fragment === '' ? '/' : fragment)
}

beforeEach(() => {
  // 每个用例都从「未登录」起步：client 顶层会从 localStorage 恢复令牌，不清就会串味。
  window.localStorage.clear()
  setTelegramWebApp(null)
  setLaunchParams('')
})

afterEach(() => {
  setTelegramWebApp(null)
  setLaunchParams('')
})

// ─────────────────────────────────────────────────────────────────────────────
describe('initData 来源（宿主注入 > URL 启动参数）', () => {
  it('容器外：两个来源都空 → 空串，isMiniAppEnvironment 为 false', async () => {
    const c = await loadClient()
    expect(c.readMiniAppInitData()).toBe('')
    expect(c.isMiniAppEnvironment()).toBe(false)
  })

  it('宿主注入了 initData → 原样返回（**不做**验签、不再编码）', async () => {
    const raw = 'auth_date=1758600000&query_id=AAHdF6IQAAAAAN0XohDhrOrc&hash=cafe1234'
    setTelegramWebApp(raw)
    const c = await loadClient()

    expect(c.readMiniAppInitData()).toBe(raw)
    expect(c.isMiniAppEnvironment()).toBe(true)
  })

  it('宿主未注入、URL 片段里带 tgWebAppData → 解码后返回', async () => {
    const raw = 'auth_date=1758600000&user=%7B%22id%22%3A42%7D&hash=cafe1234'
    setLaunchParams(`#tgWebAppVersion=7.0&tgWebAppData=${encodeURIComponent(raw)}`)
    const c = await loadClient()

    expect(c.readMiniAppInitData()).toBe(raw)
    expect(c.isMiniAppEnvironment()).toBe(true)
  })

  it('启动参数在 query 上（不在片段里）也认', async () => {
    const raw = 'auth_date=1758600001&hash=beef5678'
    setLaunchParams(`/?tgWebAppData=${encodeURIComponent(raw)}`)
    const c = await loadClient()

    expect(c.readMiniAppInitData()).toBe(raw)
  })

  it('宿主对象存在但 initData 为空串 → 回落到 URL 启动参数', async () => {
    const raw = 'auth_date=1758600002&hash=feed0001'
    setTelegramWebApp('')
    setLaunchParams(`#tgWebAppData=${encodeURIComponent(raw)}`)
    const c = await loadClient()

    expect(c.readMiniAppInitData()).toBe(raw)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
describe('miniAppLogin：initData 换会话令牌', () => {
  it('200 → 命中 /admin/auth/miniapp、body 是原始 initData，且令牌落盘（刷新不掉线）', async () => {
    const c = await loadClient()
    let seen: { url?: string; method?: string; data?: unknown } | null = null
    c.http.defaults.adapter = async (config) => {
      seen = config
      return {
        data: {
          token: 'tok-miniapp',
          subjectType: 'TG_USER',
          subjectId: 424242,
          role: null,
          permissions: [],
        },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }

    const raw = 'auth_date=1758600000&hash=cafe1234'
    const info = await c.miniAppLogin(raw)

    expect(seen!.url).toBe('/admin/auth/miniapp')
    expect(seen!.method).toBe('post')
    expect(JSON.parse(String(seen!.data))).toEqual({ initData: raw })
    expect(info.subjectType).toBe('TG_USER')
    expect(c.getToken()).toBe('tok-miniapp')
    expect(c.hasCredentials()).toBe(true)
  })

  it('403 → 抛服务端文案，且**不**写令牌（非白名单用户不该留下半截会话）', async () => {
    const c = await loadClient()
    c.http.defaults.adapter = async (config) => ({
      data: { error: '该 Telegram 用户不在授权名单内' },
      status: 403,
      statusText: '',
      headers: {},
      config,
    })

    await expect(c.miniAppLogin('auth_date=1&hash=x')).rejects.toThrow('该 Telegram 用户不在授权名单内')
    expect(c.hasCredentials()).toBe(false)
    expect(c.getToken()).toBe('')
  })

  it('401（验签不过）→ 用兜底文案，不把「未通过校验」说成「密码错误」', async () => {
    const c = await loadClient()
    c.http.defaults.adapter = async (config) => ({
      data: {}, status: 401, statusText: '', headers: {}, config,
    })

    await expect(c.miniAppLogin('auth_date=1&hash=x')).rejects.toThrow('initData 未通过服务端校验')
    expect(c.hasCredentials()).toBe(false)
  })
})
