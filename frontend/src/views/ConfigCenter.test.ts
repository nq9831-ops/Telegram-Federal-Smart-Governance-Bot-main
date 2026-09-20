// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus, { ElMessageBox } from 'element-plus'
import ConfigCenter from './ConfigCenter.vue'
import type { ConfigItem } from '../api/types'

/**
 * 配置中心视图测试。
 *
 * <p>两条判据都落在「有真实后果」的地方：
 * ① 密钥行**不得出现编辑控件**（虽然后端也会拦，但界面若能编辑就是误导）；
 * ② 重启必须**成对断言**（取消→0 次、确认→1 次）——只测「确认后发了请求」测不出二次确认。
 */

vi.mock('../api/client', () => ({
  fetchConfig: vi.fn(),
  updateConfig: vi.fn(),
  clearConfig: vi.fn(),
  restartSystem: vi.fn(),
  describeError: vi.fn((error: unknown) => `ERR:${String(error)}`),
}))

// eslint-disable-next-line import/first
import { fetchConfig, restartSystem } from '../api/client'

const fetchMock = vi.mocked(fetchConfig)
const restartMock = vi.mocked(restartSystem)

function secretItem(): ConfigItem {
  return {
    key: 'tgg.webhook.bot-token',
    category: 'SECRET',
    type: 'STRING',
    secret: true,
    editable: false,
    restartRequired: true,
    effectiveValue: '***',
    defaultValue: null,
    source: 'environment',
    description: 'Bot token',
  }
}

function hotItem(): ConfigItem {
  return {
    key: 'tgg.admin.overdue-remind-hours',
    category: 'RUNTIME',
    type: 'HOURS',
    secret: false,
    editable: true,
    restartRequired: false,
    effectiveValue: '24',
    defaultValue: '24',
    source: 'default',
    description: '提醒阈值',
  }
}

function mountView() {
  // ThemeToggle 用 useTheme()（Pinia store），故必须装 Pinia——否则 setup 抛错。
  return mount(ConfigCenter, {
    props: { operator: '777' },
    global: { plugins: [ElementPlus, createPinia()] },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('ConfigCenter · 密钥只读', () => {
  it('密钥行只回显打码值，不给「保存」入口', async () => {
    fetchMock.mockResolvedValue([secretItem()])
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('***')
    expect(wrapper.findAll('button').some((b) => b.text().includes('保存'))).toBe(false)
  })

  it('可写行给出「保存」入口', async () => {
    fetchMock.mockResolvedValue([hotItem()])
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findAll('button').some((b) => b.text().includes('保存'))).toBe(true)
    expect(wrapper.text()).toContain('热生效')
  })
})

describe('ConfigCenter · 重启需二次确认', () => {
  it('用户取消 → 绝不发出重启请求', async () => {
    fetchMock.mockResolvedValue([])
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never)
    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find((b) => b.text().includes('重启服务'))
    expect(button, '应有「重启服务」按钮').toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(restartMock).not.toHaveBeenCalled()
  })

  it('用户确认 → 发出重启请求', async () => {
    fetchMock.mockResolvedValue([])
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    restartMock.mockResolvedValue(undefined)
    const wrapper = mountView()
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text().includes('重启服务'))!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(restartMock).toHaveBeenCalledTimes(1)
  })
})
