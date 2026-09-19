// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import DecisionDrawer from './DecisionDrawer.vue'
import type { ApprovalItem } from '../api/types'

/**
 * `DecisionDrawer` 的裁决路径测试。
 *
 * <p><b>为什么只有它值得单独测</b>：整个前端里，只有这里的按钮有**不可逆的对外后果**——
 * 「推翻」会立即解封当事人（作用于真实 Telegram）。其余组件都是只读展示。
 * 所以判据也必须落在这一条上：**用户取消二次确认时，绝不能发出请求**。
 *
 * <p>只测「确认后发了请求」是不够的——那测不出二次确认的存在：
 * 把整个 confirm 删掉，那个用例照样绿。必须**成对**断言（取消→0 次；确认→1 次），
 * 二次确认才真正被钉住。
 *
 * <p>⚠️ 本文件用 `// @vitest-environment jsdom` 逐文件开启 DOM 环境——
 * 另两个测试（theme / api client）是纯逻辑，留在默认 node 环境即可，不必全局改配置。
 */

// 隔离网络层：本测试只关心「点了按钮之后，组件决定发不发请求」。
vi.mock('../api/client', () => ({
  decide: vi.fn(),
  describeError: vi.fn((error: unknown) => `ERR:${String(error)}`),
}))

// eslint-disable-next-line import/first
import { decide } from '../api/client'

const decideMock = vi.mocked(decide)

function pendingItem(): ApprovalItem {
  return {
    id: 42,
    chatId: -100,
    userId: 7,
    ruleIds: 'SCAM',
    riskLevel: 'HIGH',
    hardLine: false,
    status: 'PENDING',
    createdAt: '2026-09-19T00:00:00Z',
    decidedAt: null,
    decidedBy: null,
    note: null,
    ageHours: 3,
    overdue: false,
  }
}

/** 挂载抽屉。stub 掉 el-drawer 以免内容被 teleport 到 body、导致按钮查不到。 */
function mountDrawer() {
  return mount(DecisionDrawer, {
    props: { visible: true, item: pendingItem() },
    global: {
      plugins: [ElementPlus],
      stubs: { ElDrawer: { template: '<div><slot /></div>' } },
    },
  })
}

/** 按可见文案取按钮——比按下标取稳（按钮顺序将来可能变）。 */
function buttonByText(wrapper: ReturnType<typeof mountDrawer>, text: string) {
  const button = wrapper.findAll('button').find((b) => b.text().includes(text))
  expect(button, `应存在含「${text}」的按钮`).toBeTruthy()
  return button!
}

beforeEach(() => {
  vi.clearAllMocks()
  decideMock.mockResolvedValue({ result: 'DECIDED', status: 'REJECTED' })
})

describe('DecisionDrawer · 推翻需二次确认', () => {
  it('用户取消 → 绝不发出裁决请求（这是二次确认的全部意义）', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never)
    const wrapper = mountDrawer()

    await buttonByText(wrapper, '推翻').trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(decideMock).not.toHaveBeenCalled()
  })

  it('用户确认 → 以 REJECTED 发出裁决请求', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountDrawer()

    await buttonByText(wrapper, '推翻').trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(decideMock).toHaveBeenCalledTimes(1)
    expect(decideMock.mock.calls[0][0]).toBe(42)
    expect(decideMock.mock.calls[0][1]).toMatchObject({ decision: 'REJECTED' })
  })

  it('维持（无不可逆后果）不应弹确认框——否则每次维持都被多余打断', async () => {
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountDrawer()

    await buttonByText(wrapper, '维持').trigger('click')
    await flushPromises()

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(decideMock.mock.calls[0][1]).toMatchObject({ decision: 'APPROVED' })
  })
})

describe('DecisionDrawer · 结果反馈', () => {
  it('后端返回 ALREADY_DECIDED 时提示幂等，而不是谎报「已推翻」', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const warningSpy = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never)
    const successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never)
    decideMock.mockResolvedValue({ result: 'ALREADY_DECIDED', status: 'APPROVED' })

    const wrapper = mountDrawer()
    await buttonByText(wrapper, '推翻').trigger('click')
    await flushPromises()

    expect(warningSpy).toHaveBeenCalled()
    expect(successSpy).not.toHaveBeenCalled()
  })

  it('裁决成功后才关闭抽屉并通知父组件刷新', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never)
    const wrapper = mountDrawer()

    await buttonByText(wrapper, '推翻').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
    expect(wrapper.emitted('decided')).toBeTruthy()
  })
})
