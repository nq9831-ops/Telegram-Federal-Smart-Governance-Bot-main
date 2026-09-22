// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import TodoCenter from './TodoCenter.vue'

/**
 * 「待办中心」视图测试。
 *
 * <p>判据落在两件真实后果上：① 正常状态案件行**渲染出来**；
 * ② 加载失败时页面**保留持久错误提示**——此前只弹一次 toast、页面退化成空表，
 * 用户刷新后只看到「没有符合条件的案件」，无从分辨「真没有」还是「没拉到」。
 */

vi.mock('../api/client', () => ({
  fetchApprovals: vi.fn(),
  fetchStats: vi.fn(),
  fetchConfig: vi.fn(),
  describeError: vi.fn((error: unknown) => `ERR:${String(error)}`),
  FORBIDDEN_APPROVAL: 'APPROVAL_FORBIDDEN',
  // DecisionDrawer（子组件）也从 client 取这三个，mock 里必须齐备，否则 import 解构为 undefined。
  decide: vi.fn(),
}))

// eslint-disable-next-line import/first
import { fetchApprovals, fetchConfig, fetchStats } from '../api/client'

const approvalsMock = vi.mocked(fetchApprovals)
const statsMock = vi.mocked(fetchStats)
const configMock = vi.mocked(fetchConfig)

function mountView() {
  return mount(TodoCenter, {
    props: { operator: '777' },
    // ThemeToggle 用 useTheme()（Pinia store），必须装 Pinia——否则 setup 抛错。
    global: { plugins: [ElementPlus, createPinia()] },
  })
}

/** 断言前把空白折叠掉：模板里的换行/缩进会让 toContain 逐字比较失配。 */
const flat = (wrapper: ReturnType<typeof mountView>): string =>
  wrapper.text().replace(/\s+/g, '')

/** 三路并发都成功的最小桩（stats 字段齐全，避免模板读到 undefined）。 */
function resolveAll(items: unknown[] = []): void {
  approvalsMock.mockResolvedValue({ items, total: items.length } as never)
  statsMock.mockResolvedValue({
    pending: 0,
    hardLinePending: 0,
    overdueRemind: 0,
    overdueEscalate: 0,
    avgDecisionHours: null,
  } as never)
  configMock.mockResolvedValue([])
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('TodoCenter · 待办中心', () => {
  it('加载成功 → 渲染案件行', async () => {
    resolveAll([
      {
        id: 12,
        hardLine: false,
        riskLevel: 'LOW',
        userId: 1,
        chatId: -100,
        userHitCount: 0,
        userHardLineCount: 0,
        ruleIds: 'r1',
        ageHours: 2,
        status: 'PENDING',
        overdue: false,
      },
    ])
    const wrapper = mountView()
    await flushPromises()

    expect(flat(wrapper)).toContain('12')
  })

  it('加载失败 → 页面保留持久错误提示（不只 toast）', async () => {
    approvalsMock.mockRejectedValue(new Error('boom'))
    statsMock.mockRejectedValue(new Error('boom'))
    configMock.mockRejectedValue(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.el-alert--error').exists()).toBe(true)
    expect(flat(wrapper)).toContain('加载失败')
    expect(flat(wrapper)).toContain('ERR:')
  })
})
