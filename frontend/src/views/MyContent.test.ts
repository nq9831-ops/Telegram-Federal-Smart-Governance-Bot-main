// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import MyContent from './MyContent.vue'

/**
 * 「我的收录」视图测试。
 *
 * <p>判据落在两件真实后果上：① 自己提交的行**渲染出来**（含编号/群/标题/状态）；
 * ② 无数据时给**空态**而不是当成错误——后台账号恒返回空数组，报错会误导。
 */

vi.mock('../api/client', () => ({
  fetchMyListings: vi.fn(),
  describeError: vi.fn((error: unknown) => `ERR:${String(error)}`),
}))

// eslint-disable-next-line import/first
import { fetchMyListings } from '../api/client'

const fetchMock = vi.mocked(fetchMyListings)

function mountView() {
  return mount(MyContent, {
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

describe('MyContent · 我的收录（数据范围 OWN）', () => {
  it('渲染自己提交的收录行（编号 / 群 / 标题 / 状态）', async () => {
    fetchMock.mockResolvedValue([
      { id: 7, chatId: -100900999, title: '测试群', status: 'ACTIVE', createdAt: '2026-09-21T00:00:00Z' },
    ])
    const wrapper = mountView()
    await flushPromises()

    const text = flat(wrapper)
    expect(text).toContain('测试群')
    expect(text).toContain('#7')
    expect(text).toContain('-100900999')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('无数据 → 空态（后台账号恒空，不应报错）', async () => {
    fetchMock.mockResolvedValue([])
    const wrapper = mountView()
    await flushPromises()

    expect(flat(wrapper)).toContain('还没有提交过收录')
  })

  it('拉取失败 → 显示错误文案而不白屏', async () => {
    fetchMock.mockRejectedValue(new Error('boom'))
    const wrapper = mountView()
    await flushPromises()

    expect(flat(wrapper)).toContain('ERR:')
  })
})
