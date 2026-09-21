// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import { AxiosError } from 'axios'
import AuditCenter from './AuditCenter.vue'

/**
 * 「审计」视图测试。
 *
 * <p>判据落在两件真实后果上：① 审计行**渲染出来**（动作 / 主体 / 案件）；
 * ② 无权限（403）时给**「无权限」空态**而不是崩或白屏——持 `AUDIT_READ` 之外的主体本就会拿到 403。
 */

vi.mock('../api/client', () => ({
  fetchAuditRecent: vi.fn(),
  describeError: vi.fn((error: unknown) => `ERR:${String(error)}`),
  AUDIT_MAX_LIMIT: 200,
}))

// eslint-disable-next-line import/first
import { fetchAuditRecent } from '../api/client'

const fetchMock = vi.mocked(fetchAuditRecent)

/** 造一个带状态码的 axios 错误（与 client.ts 的 describeError 分支同源）。 */
function axiosError(status: number): AxiosError {
  return new AxiosError('boom', 'ERR_BAD_REQUEST', undefined, undefined, {
    status, statusText: '', headers: {}, config: {} as never, data: { error: 'no' },
  })
}

function mountView() {
  return mount(AuditCenter, {
    props: { operator: '777' },
    // ThemeToggle 用 useTheme()（Pinia store），必须装 Pinia——否则 setup 抛错。
    global: { plugins: [ElementPlus, createPinia()] },
  })
}

/** 断言前把空白折叠掉：模板里的换行/缩进会让 toContain 逐字比较失配。 */
const flat = (wrapper: ReturnType<typeof mountView>): string =>
  wrapper.text().replace(/\s+/g, '')

beforeEach(() => {
  vi.clearAllMocks()
})

describe('AuditCenter · 审计（只读）', () => {
  it('渲染审计行：动作 + 主体（类型与 id 成对，避免串号）', async () => {
    fetchMock.mockResolvedValue([
      {
        id: 1, actorType: 'ADMIN_ACCOUNT', actorId: 5, action: 'admin.config.write',
        target: -100900999, caseId: null, outcome: 'OK',
        detail: 'tgg.admin.session-ttl-hours=24', occurredAt: '2026-09-21T00:00:00Z',
      },
    ])
    const wrapper = mountView()
    await flushPromises()

    const text = flat(wrapper)
    expect(text).toContain('admin.config.write')
    expect(text).toContain('后台账号#5')
    expect(text).toContain('tgg.admin.session-ttl-hours')
  })

  it('403 → 「无权限」空态（而不是崩或白屏）', async () => {
    fetchMock.mockRejectedValue(axiosError(403))
    const wrapper = mountView()
    await flushPromises()

    expect(flat(wrapper)).toContain('无权限')
  })

  it('其它失败 → 显示错误文案', async () => {
    fetchMock.mockRejectedValue(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()

    expect(flat(wrapper)).toContain('ERR:')
  })
})
