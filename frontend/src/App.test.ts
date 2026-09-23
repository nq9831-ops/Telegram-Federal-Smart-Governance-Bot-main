// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import App from './App.vue'

/**
 * 门禁页的**入口接线**测试（模块十二 · gap-ESC-04「入口接 App.vue 的分区」）。
 *
 * <p>判据只有两条，但都指向真实后果：
 * ① 容器内打开时，Mini App 入口**真的**出现（否则 TG 用户面对一个自己用不了的账号密码表单）；
 * ② 容器外打开时，它**绝不**出现、也绝不发请求（浏览器里的运营者不该看到一条永远失败的登录路径）。
 *
 * <p>`fetchLoginConfig` 一律回空 bot username：本用例不碰 Login Widget 的 CDN 脚本注入。
 * <p>⚠️ 未在真实环境验证：无真机 initData、无 Telegram WebView。
 */

vi.mock('./api/client', () => ({
  // 会话 store 初始化即调用——缺一条 setup 就抛错。
  hasCredentials: vi.fn(() => false),
  setOnUnauthorized: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  telegramLogin: vi.fn(),
  // 门禁页与 Mini App 入口用到。
  fetchLoginConfig: vi.fn(),
  isMiniAppEnvironment: vi.fn(() => false),
  readMiniAppInitData: vi.fn(() => ''),
  miniAppLogin: vi.fn(),
  describeError: vi.fn((error: unknown) => `ERR:${String(error)}`),
}))

// eslint-disable-next-line import/first
import { fetchLoginConfig, isMiniAppEnvironment, miniAppLogin, readMiniAppInitData } from './api/client'

const envMock = vi.mocked(isMiniAppEnvironment)
const initDataMock = vi.mocked(readMiniAppInitData)
const loginMock = vi.mocked(miniAppLogin)

const RAW_INIT_DATA = 'auth_date=1758600000&query_id=AAHdF6IQAAAAAN0XohDhrOrc&hash=cafe1234'

function mountApp() {
  const pinia = createPinia()
  return mount(App, { global: { plugins: [ElementPlus, pinia] } })
}

/** 断言前把空白折叠掉：模板里的换行/缩进会让 toContain 逐字比较失配。 */
const flat = (wrapper: ReturnType<typeof mountApp>): string => wrapper.text().replace(/\s+/g, '')

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(fetchLoginConfig).mockResolvedValue({ telegramBotUsername: '' })
  envMock.mockReturnValue(false)
  initDataMock.mockReturnValue('')
})

describe('门禁页 · Mini App 入口接线', () => {
  it('容器内：渲染 Mini App 入口，并把原始 initData 交给服务端', async () => {
    envMock.mockReturnValue(true)
    initDataMock.mockReturnValue(RAW_INIT_DATA)
    // 让换会话失败：门禁页不消失，才能在同一个 DOM 上同时断言「入口在」+「请求发了」。
    loginMock.mockRejectedValue(new Error('not in allowlist'))

    const wrapper = mountApp()
    await flushPromises()

    expect(loginMock).toHaveBeenCalledWith(RAW_INIT_DATA)
    expect(flat(wrapper)).toContain('未能用Telegram身份登录')
    // 兜底：容器里换不到会话时，账号密码表单仍在（不被入口顶掉）。
    expect(flat(wrapper)).toContain('登录名')
  })

  it('容器外：不渲染入口、不发请求，只给账号密码登录', async () => {
    const wrapper = mountApp()
    await flushPromises()

    expect(loginMock, '容器外拿不到 initData，不该发这条请求').not.toHaveBeenCalled()
    expect(flat(wrapper)).not.toContain('正在用Telegram身份登录')
    expect(flat(wrapper)).not.toContain('未能用Telegram身份登录')
    expect(wrapper.find('input').exists(), '账号密码登录表单仍应可用').toBe(true)
  })
})
