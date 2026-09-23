// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { resolveGuard } from './router'

/**
 * 路由守卫的权限语义（模块十二 Wave 2a：门禁从组件挪进守卫）。
 *
 * <p><b>为什么不测"渲染出哪个视图"而测判定函数</b>：权限是安全面，
 * 需要能对**每个分支**取确定结论（越权必须被挡、且挡到确定的目标），
 * 而不是靠挂载整棵组件树、断言某段文本出现与否。
 *
 * <p>它守的是本项目已经踩过的那类洞：把校验写在组件里，换个入口就绕过。
 * 现在判定只有一个来源（`resolveGuard`），三档权限各有独立用例。
 */
const session = (over: Partial<{
  authenticated: boolean
  canReadAudit: boolean
  canReadCredit: boolean
  role: string
}> = {}) => ({
  authenticated: true,
  canReadAudit: false,
  canReadCredit: false,
  role: 'OPERATOR',
  ...over,
})

describe('路由守卫 · 权限判定', () => {
  it('未认证：取消导航（门禁页由 App.vue 渲染，不是一条路由）', () => {
    expect(resolveGuard({ meta: {} }, session({ authenticated: false }))).toBe(false)
    expect(resolveGuard({ meta: { requires: 'super' } }, session({ authenticated: false }))).toBe(false)
  })

  it('无门槛页面：认证后放行', () => {
    for (const path of ['/todos', '/config', '/mine']) {
      expect(resolveGuard({ meta: {} }, session()), path).toBe(true)
    }
  })

  it('审计页：须 canReadAudit，否则回退待办中心', () => {
    expect(resolveGuard({ meta: { requires: 'audit' } }, session())).toBe('/todos')
    expect(resolveGuard({ meta: { requires: 'audit' } }, session({ canReadAudit: true }))).toBe(true)
  })

  it('信用页：须 canReadCredit，否则回退待办中心', () => {
    expect(resolveGuard({ meta: { requires: 'credit' } }, session())).toBe('/todos')
    expect(resolveGuard({ meta: { requires: 'credit' } }, session({ canReadCredit: true }))).toBe(true)
  })

  it('账号管理：仅 SUPER_ADMIN，审计权限不得顶替', () => {
    expect(resolveGuard({ meta: { requires: 'super' } }, session())).toBe('/todos')
    expect(
      resolveGuard({ meta: { requires: 'super' } }, session({ canReadAudit: true })),
      '有审计权限不等于能管账号',
    ).toBe('/todos')
    expect(resolveGuard({ meta: { requires: 'super' } }, session({ role: 'SUPER_ADMIN' }))).toBe(true)
  })
})
