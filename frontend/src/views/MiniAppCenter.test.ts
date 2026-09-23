// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import MiniAppCenter from './MiniAppCenter.vue'

/**
 * 「Mini App 入口」视图测试（模块十二 · gap-ESC-04）。
 *
 * <p>判据落在**两条真实后果**上：
 * ① 容器内打开时，initData **真的**被送去换会话（不是「显示了按钮」就算数）——
 *    搬错串（比如搬了验签后的字段、或空串）服务端一律拒，且拒得静默；
 * ② 容器外打开**绝不发请求**——拿不到 initData 就必然 401，发出去只会制造一条无意义的失败日志。
 *
 * <p>⚠️ 未在真实环境验证：无真机 initData、无 Telegram WebView。这里验的是「拿到 initData 之后的接线」。
 * `<p>mock 工厂必须列出会话 store 在初始化时就要用的导出`（`hasCredentials` / `setOnUnauthorized`），
 * 漏一个 setup 就抛错——与报告 C 节「vi.mock 工厂须同步新导出」同源。
 */

vi.mock('../api/client', () => ({
  // 会话 store 在 `useSession()` 初始化时即调用——mock 里缺一条就会炸在 setup。
  hasCredentials: vi.fn(() => false),
  setOnUnauthorized: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  telegramLogin: vi.fn(),
  // 本视图真正用到的：读原始 initData + 换会话 + 错误翻译。
  readMiniAppInitData: vi.fn(),
  miniAppLogin: vi.fn(),
  describeError: vi.fn((error: unknown) => `ERR:${String(error)}`),
}))

// eslint-disable-next-line import/first
import { hasCredentials, miniAppLogin, readMiniAppInitData } from '../api/client'
// eslint-disable-next-line import/first
import { useSession } from '../stores/session'

const initDataMock = vi.mocked(readMiniAppInitData)
const loginMock = vi.mocked(miniAppLogin)
const hasCredentialsMock = vi.mocked(hasCredentials)

const RAW_INIT_DATA = 'auth_date=1758600000&query_id=AAHdF6IQAAAAAN0XohDhrOrc&hash=cafe1234'

/** 一条真实形态的 Mini App login 响应（后端签发的是同一套 `AdminSessionFilter` 会话）。 */
const SESSION = {
  token: 'tok-miniapp',
  subjectType: 'TG_USER' as const,
  subjectId: 424242,
  // TG 用户不是后台账号，故 role 为 null（与 LoginResponse 的取值约定一致）。
  role: null,
  permissions: [],
}

function mountView() {
  // ThemeToggle 未用，但视图走 useSession()（Pinia store）——必须装 Pinia，否则 setup 抛错；
  // 同一个 pinia 实例交给 useSession(pinia)，断言才看得到视图真正改动的那份状态。
  const pinia = createPinia()
  const wrapper = mount(MiniAppCenter, { global: { plugins: [ElementPlus, pinia] } })
  return { wrapper, session: useSession(pinia) }
}

/** 断言前把空白折叠掉：模板里的换行/缩进会让 toContain 逐字比较失配。 */
const flat = (wrapper: ReturnType<typeof mountView>['wrapper']): string =>
  wrapper.text().replace(/\s+/g, '')

beforeEach(() => {
  vi.clearAllMocks()
  hasCredentialsMock.mockReturnValue(false)
})

describe('MiniAppCenter · initData 换会话', () => {
  it('容器内：挂载即把**原始** initData 交给服务端，成功后呈已登录', async () => {
    initDataMock.mockReturnValue(RAW_INIT_DATA)
    loginMock.mockResolvedValue(SESSION)
    const { wrapper, session } = mountView()
    await flushPromises()

    // 搬的必须是原始 query 串本身（服务端要按字典序重算 HMAC，少一个字段就验不过）。
    expect(loginMock).toHaveBeenCalledTimes(1)
    expect(loginMock).toHaveBeenCalledWith(RAW_INIT_DATA)
    expect(session.authenticated, '换到令牌后 store 应切到已登录').toBe(true)
    expect(session.operatorLabel).toBe('TG 用户 #424242')
    expect(flat(wrapper)).toContain('已通过Telegram身份登录')
  })

  it('容器外（拿不到 initData）：一次请求都不发，直接给提示', async () => {
    initDataMock.mockReturnValue('')
    const { wrapper, session } = mountView()
    await flushPromises()

    expect(loginMock, '空 initData 换必失败，不该发这条请求').not.toHaveBeenCalled()
    expect(session.authenticated).toBe(false)
    expect(flat(wrapper)).toContain('拿不到initData')
  })

  it('验签失败：显示服务端错误文案，并给出重试入口（重试真的再发一次）', async () => {
    initDataMock.mockReturnValue(RAW_INIT_DATA)
    loginMock.mockRejectedValue(new Error('initData 签名不匹配'))
    const { wrapper, session } = mountView()
    await flushPromises()

    expect(session.authenticated).toBe(false)
    expect(flat(wrapper)).toContain('ERR:Error:initData签名不匹配')

    // 容器注入的启动参数可能晚于首帧可见——故必须留一个可再点一次的重试入口。
    const retry = wrapper.findAll('button').find((b) => b.text().includes('重新用'))
    expect(retry, '失败态应有重试按钮').toBeTruthy()
    loginMock.mockResolvedValue(SESSION)
    await retry!.trigger('click')
    await flushPromises()

    expect(loginMock).toHaveBeenCalledTimes(2)
    expect(session.authenticated).toBe(true)
  })

  it('已持有会话（刷新时 localStorage 里还有令牌）：不再多换一次', async () => {
    hasCredentialsMock.mockReturnValue(true)
    initDataMock.mockReturnValue(RAW_INIT_DATA)
    const { wrapper, session } = mountView()
    await flushPromises()

    expect(loginMock).not.toHaveBeenCalled()
    expect(session.authenticated).toBe(true)
    expect(flat(wrapper)).toContain('已通过Telegram身份登录')
  })
})
