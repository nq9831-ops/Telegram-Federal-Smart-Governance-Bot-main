// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus, { ElMessageBox } from 'element-plus'
import AccountCenter from './AccountCenter.vue'
import type { AccountView } from '../api/types'

/**
 * 「账号管理」视图测试。
 *
 * <p>判据只落在「有真实后果」的地方：停用 / 强制下线都会**立即吊销该账号的会话**，
 * 是不可逆的对外动作——必须成对断言（取消→0 次、确认→1 次）。只测「确认后发了请求」
 * 测不出二次确认是否存在（那是这类改动最容易漏的半边）。
 * 反向守卫：**启用**不该弹确认（确认泛滥会让人对确认框脱敏）。
 */

vi.mock('../api/client', () => ({
  ALL_PERMISSIONS: ['REVIEW_DECIDE'],
  PERMISSION_LABEL: { REVIEW_DECIDE: '审批裁决' },
  createAccount: vi.fn(),
  describeError: vi.fn((error: unknown) => `ERR:${String(error)}`),
  fetchAccounts: vi.fn(),
  resetAccountPassword: vi.fn(),
  revokeAccountSessions: vi.fn(),
  setAccountPermissions: vi.fn(),
  setAccountStatus: vi.fn(),
}))

// eslint-disable-next-line import/first
import { fetchAccounts, revokeAccountSessions, setAccountStatus } from '../api/client'

const fetchMock = vi.mocked(fetchAccounts)
const statusMock = vi.mocked(setAccountStatus)
const revokeMock = vi.mocked(revokeAccountSessions)

function operatorAccount(overrides: Partial<AccountView> = {}): AccountView {
  return { id: 5, username: 'tm', role: 'OPERATOR', status: 'ACTIVE', permissions: [], ...overrides }
}

function mountView() {
  return mount(AccountCenter, {
    props: { operator: '1' },
    // ThemeToggle 用 useTheme()（Pinia store），必须装 Pinia——否则 setup 抛错。
    global: { plugins: [ElementPlus, createPinia()] },
  })
}

async function setup(account: AccountView = operatorAccount()) {
  fetchMock.mockResolvedValue([account])
  const wrapper = mountView()
  await flushPromises()
  return wrapper
}

/**
 * 在**真实表格行**里找按钮。
 *
 * ⚠️ 不能对整棵树 `findAll('button')`：Element Plus 的 el-table 在 `.hidden-columns`
 * 里克隆了一份操作列（用于量列宽），那批「幽灵按钮」绑的是空行，点它拿到的是 `id=null`。
 * 必须限定在 `.el-table__body` 内，才是绑了真实 row 的按钮。
 */
const findButton = (wrapper: ReturnType<typeof mountView>, label: string) =>
  wrapper.find('.el-table__body').findAll('button').find((b) => b.text().includes(label))

beforeEach(() => {
  vi.clearAllMocks()
})

describe('AccountCenter · 停用 / 强制下线需二次确认', () => {
  it('停用：取消 → 绝不发出停用请求', async () => {
    const wrapper = await setup()
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never)

    await findButton(wrapper, '停用')!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(statusMock).not.toHaveBeenCalled()
  })

  it('停用：确认 → 以 DISABLED 发出请求', async () => {
    const wrapper = await setup()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    statusMock.mockResolvedValue(undefined)

    await findButton(wrapper, '停用')!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(statusMock).toHaveBeenCalledWith(5, 'DISABLED')
  })

  it('启用：不弹确认，直接以 ACTIVE 发出请求', async () => {
    const wrapper = await setup(operatorAccount({ status: 'DISABLED' }))
    const confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    statusMock.mockResolvedValue(undefined)

    await findButton(wrapper, '启用')!.trigger('click')
    await flushPromises()

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(statusMock).toHaveBeenCalledWith(5, 'ACTIVE')
  })

  it('强制下线：取消 → 绝不发出请求', async () => {
    const wrapper = await setup()
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel' as never)

    await findButton(wrapper, '强制下线')!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(revokeMock).not.toHaveBeenCalled()
  })

  it('强制下线：确认 → 发出请求', async () => {
    const wrapper = await setup()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    revokeMock.mockResolvedValue(undefined)

    await findButton(wrapper, '强制下线')!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(revokeMock).toHaveBeenCalledWith(5)
  })
})
