import { describe, expect, it } from 'vitest'
import { validateGate } from './gate'

/**
 * 登录门禁校验。
 *
 * 判据是「有没有真实的下一步后果」：校验失败时**不能发起登录请求**（不写凭据、不加载列表）。
 */
describe('validateGate', () => {
  it('登录名与密码都必填：缺任一个都拒绝，且不返回凭据', () => {
    for (const input of [
      { username: '', password: 'pw' },
      { username: 'root', password: '' },
      { username: '', password: '' },
      { username: '   ', password: 'pw' },
    ]) {
      const check = validateGate(input)
      expect(check.ok).toBe(false)
      expect(check).toEqual({ ok: false, reason: '登录名与密码都要填' })
    }
  })

  it('登录名去除首尾空白；密码原样保留（空格可能是密码的一部分）', () => {
    expect(validateGate({ username: '  root  ', password: ' p w ' })).toEqual({
      ok: true,
      username: 'root',
      password: ' p w ',
    })
  })
})
