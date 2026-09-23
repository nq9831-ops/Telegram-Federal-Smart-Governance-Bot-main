// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import EscrowCenter from './EscrowCenter.vue'

/**
 * 担保交易订单页的接线与呈现（模块十二 · gap-ESC-05 的前端面）。
 *
 * <p>守三件事，都指向真实后果：
 * ① 加载后**真的把订单渲染出来**（含后端给的中文状态名——前端不该自己拼一份）；
 * ② 空列表给**空态**而不是空白（空白看起来像坏了）；
 * ③ 拉取失败**给错误反馈**（403 是这里的常见态：非超管且无 FEDERATION_ADMIN）。
 */
vi.mock('../api/client', () => ({
  fetchEscrowOrders: vi.fn(),
  fetchEscrowOrder: vi.fn(),
  describeError: vi.fn((error: unknown, forbidden?: string) => `ERR:${String(error)}|${forbidden ?? ''}`),
}))

// eslint-disable-next-line import/first
import { fetchEscrowOrder, fetchEscrowOrders } from '../api/client'

const ORDER = {
  id: 42,
  state: 'DISPUTED',
  stateLabel: '争议中，等待裁决',
  buyerUserId: 11,
  sellerUserId: 22,
  amount: '100.00000000',
  currency: 'USDT',
  reason: '未收到货',
  createdAt: '2026-09-23T03:00:00Z',
  updatedAt: '2026-09-23T04:00:00Z',
}

function mountPage() {
  return mount(EscrowCenter, {
    props: { operator: 'it-op' },
    global: { plugins: [ElementPlus] },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('担保交易订单页', () => {
  it('加载后渲染订单（状态中文名取自后端 stateLabel）', async () => {
    vi.mocked(fetchEscrowOrders).mockResolvedValue({ items: [ORDER], page: 0, size: 50, total: 1 })

    const wrapper = mountPage()
    await flushPromises()

    expect(fetchEscrowOrders).toHaveBeenCalled()
    expect(wrapper.text()).toContain('争议中，等待裁决')
    expect(wrapper.text()).toContain('100.00000000 USDT')
  })

  it('空列表显示空态而不是空白', async () => {
    vi.mocked(fetchEscrowOrders).mockResolvedValue({ items: [], page: 0, size: 50, total: 0 })

    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('没有符合条件的订单')
  })

  it('拉取失败给错误反馈，不静默', async () => {
    vi.mocked(fetchEscrowOrders).mockRejectedValue(new Error('forbidden'))

    const wrapper = mountPage()
    await flushPromises()

    // describeError 被调用即说明错误路径被走到（其返回值经 ElMessage 呈现，不在组件 DOM 里）。
    const { describeError } = await import('../api/client')
    expect(describeError).toHaveBeenCalled()
    expect(wrapper.text()).toContain('担保交易')
  })

  it('点击刷新会重新拉取', async () => {
    vi.mocked(fetchEscrowOrders).mockResolvedValue({ items: [], page: 0, size: 50, total: 0 })

    const wrapper = mountPage()
    await flushPromises()
    const before = vi.mocked(fetchEscrowOrders).mock.calls.length

    const refresh = wrapper.findAll('button').find((b) => b.text().includes('刷新'))
    expect(refresh, '刷新按钮应存在').toBeTruthy()
    await refresh!.trigger('click')
    await flushPromises()

    expect(vi.mocked(fetchEscrowOrders).mock.calls.length).toBeGreaterThan(before)
  })

  it('点行打开详情（走详情端点）', async () => {
    vi.mocked(fetchEscrowOrders).mockResolvedValue({ items: [ORDER], page: 0, size: 50, total: 1 })
    vi.mocked(fetchEscrowOrder).mockResolvedValue(ORDER)

    const wrapper = mountPage()
    await flushPromises()

    await wrapper.find('tbody tr').trigger('click')
    await flushPromises()

    expect(fetchEscrowOrder).toHaveBeenCalledWith(42)
  })
})
