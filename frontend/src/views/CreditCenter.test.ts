// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import { AxiosError } from 'axios'
import CreditCenter from './CreditCenter.vue'

/**
 * 「信用」视图测试。
 *
 * <p>判据落在两件真实后果上：① 流水行**渲染出来**（事件类型 / 主体 / 分值变化）；
 * ② 无权限（403）时给**「无权限」空态**而不是崩或白屏——未持 `CREDIT_READ` 的主体本就会拿到 403。
 */

vi.mock('../api/client', () => ({
  fetchCreditEvents: vi.fn(),
  fetchCreditScores: vi.fn(),
  describeError: vi.fn((error: unknown) => `ERR:${String(error)}`),
  CREDIT_MAX_PAGE_SIZE: 200,
}))

// eslint-disable-next-line import/first
import { fetchCreditEvents, fetchCreditScores } from '../api/client'

const eventsMock = vi.mocked(fetchCreditEvents)
const scoresMock = vi.mocked(fetchCreditScores)

/** 造一个带状态码的 axios 错误（与 client.ts 的 describeError 分支同源）。 */
function axiosError(status: number): AxiosError {
  return new AxiosError('boom', 'ERR_BAD_REQUEST', undefined, undefined, {
    status, statusText: '', headers: {}, config: {} as never, data: { error: 'no' },
  })
}

function mountView() {
  return mount(CreditCenter, {
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

describe('CreditCenter · 信用（只读）', () => {
  it('渲染流水行：事件类型 + 主体（类型与 id 成对，避免串号）+ 分值变化', async () => {
    eventsMock.mockResolvedValue({
      items: [
        {
          id: 1, subjectType: 'INDIVIDUAL', subjectId: 100001, eventType: 'MODERATION_HIT',
          severity: 'HIGH', hardLine: true, scoreBefore: 100, scoreDelta: -30, scoreAfter: 70,
          source: 'moderation', occurredAt: '2026-09-22T00:00:00Z',
        },
      ],
      total: 1, page: 0, size: 50,
    })
    const wrapper = mountView()
    await flushPromises()

    const text = flat(wrapper)
    expect(text).toContain('MODERATION_HIT')
    expect(text).toContain('个人#100001')
    expect(text).toContain('100→70')
  })

  it('403 → 「无权限」空态（而不是崩或白屏）', async () => {
    eventsMock.mockRejectedValue(axiosError(403))
    const wrapper = mountView()
    await flushPromises()

    expect(flat(wrapper)).toContain('无权限')
  })

  it('其它失败 → 显示错误文案', async () => {
    eventsMock.mockRejectedValue(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()

    expect(flat(wrapper)).toContain('ERR:')
  })

  it('切到账本页 → 调 fetchCreditScores 并渲染账本行', async () => {
    scoresMock.mockResolvedValue({
      items: [
        { id: 9, subjectType: 'GROUP', subjectId: -100900999, score: 120, updatedAt: '2026-09-22T01:00:00Z' },
      ],
      total: 1, page: 0, size: 50,
    })
    const wrapper = mountView()
    await flushPromises()

    // 切页：点击「账本」tab
    const tab = wrapper.findAll('*').find((n) => n.text() === '账本')
    await tab!.trigger('click')
    await flushPromises()

    expect(flat(wrapper)).toContain('群组#-100900999')
    expect(flat(wrapper)).toContain('120')
  })
})
