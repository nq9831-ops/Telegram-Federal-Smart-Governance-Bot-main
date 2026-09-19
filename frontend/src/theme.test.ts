import { describe, expect, it } from 'vitest'
import { nextTheme, resolveInitialTheme } from './theme'

/**
 * 主题解析的纯逻辑测试。
 *
 * 为什么这几条值得单独测：它们全都是**静默失效**型的错误——
 * 主题选错了不会报错、不会崩，只是"打开时颜色不对"，很可能几周都没人发现，
 * 或者被发现时被归咎于"显示器/浏览器"。所以把判据钉在测试里。
 */
describe('resolveInitialTheme', () => {
  it('用户显式选过就以用户为准（压过系统偏好）', () => {
    expect(resolveInitialTheme('light', true)).toBe('light')
    expect(resolveInitialTheme('dark', false)).toBe('dark')
  })

  it('用户没选过就跟随系统', () => {
    expect(resolveInitialTheme(null, true)).toBe('dark')
    expect(resolveInitialTheme(null, false)).toBe('light')
  })

  it('存储里是无法识别的值时，回落到系统偏好', () => {
    // 场景：老版本写过别的值、用户手改过 localStorage、或将来改了命名。
    // 回落而不是抛错——首屏渲染路径上抛错会白屏，代价远大于"主题猜错一次"。
    expect(resolveInitialTheme('blue', true)).toBe('dark')
    expect(resolveInitialTheme('', false)).toBe('light')
    expect(resolveInitialTheme('DARK', false)).toBe('light')
  })
})

describe('nextTheme', () => {
  it('两态互切', () => {
    expect(nextTheme('dark')).toBe('light')
    expect(nextTheme('light')).toBe('dark')
  })

  it('切换是可逆的（切两次回到原样）', () => {
    expect(nextTheme(nextTheme('dark'))).toBe('dark')
    expect(nextTheme(nextTheme('light'))).toBe('light')
  })
})
