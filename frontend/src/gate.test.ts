import { describe, expect, it } from 'vitest'
import { validateGate } from './gate'

/**
 * 登录门禁校验。
 *
 * 判据是「有没有真实的下一步后果」：校验失败时**不能进入主界面**（不写凭据、不加载列表），
 * 否则等于门禁形同虚设。故这里既断言失败原因，也断言失败时**不返回凭据**。
 */
describe('validateGate', () => {
  it('两个字段都必填：缺任一个都拒绝，且不返回凭据', () => {
    for (const input of [
      { token: '', operatorId: '42' },
      { token: 'tok', operatorId: '' },
      { token: '', operatorId: '' },
    ]) {
      const check = validateGate(input)
      expect(check.ok).toBe(false)
      expect(check).toEqual({ ok: false, reason: 'API 令牌与操作人 ID 都要填' })
    }
  })

  it('纯空白视同为空（用户误敲空格不得蒙混进门）', () => {
    expect(validateGate({ token: '   ', operatorId: '42' }).ok).toBe(false)
    expect(validateGate({ token: 'tok', operatorId: '  ' }).ok).toBe(false)
  })

  it('操作人 ID 必须是数字：非数字一律拒绝并说明是 ID 的问题', () => {
    for (const operatorId of ['abc', '42x', '-1', '4.2', '+42']) {
      const check = validateGate({ token: 'tok', operatorId })
      expect(check.ok, `「${operatorId}」不应通过`).toBe(false)
      expect(check).toEqual({ ok: false, reason: '操作人 ID 应是数字（Telegram userId）' })
    }
  })

  it('合法输入：通过并回传**去除首尾空白**的值（写进 localStorage 的必须是干净值）', () => {
    expect(validateGate({ token: '  tok  ', operatorId: ' 42 ' })).toEqual({
      ok: true,
      token: 'tok',
      operatorId: '42',
    })
  })

  it('通过时 operatorId 是字符串形态的纯数字（0 也合法——不因真值判断而误拒）', () => {
    expect(validateGate({ token: 'tok', operatorId: '0' })).toEqual({
      ok: true,
      token: 'tok',
      operatorId: '0',
    })
  })
})
