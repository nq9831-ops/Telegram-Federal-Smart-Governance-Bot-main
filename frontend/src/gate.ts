/**
 * 登录门禁的输入校验（纯逻辑，便于独立测试）。
 *
 * 为什么先在前端挡一道：后端登录端点对「登录名不存在 / 停用 / 锁定 / 密码错误」一律回 401
 * （不泄露成因）。若把空值直接发出去，用户只会看到「登录名或密码错误」，
 * 无从判断是**没填**还是**填错了**——前者是输入问题，后者要重新核对凭据。
 */

export interface GateInput {
  username: string
  password: string
}

export type GateCheck =
  | { ok: true; username: string; password: string }
  | { ok: false; reason: string }

export function validateGate(input: GateInput): GateCheck {
  const username = input.username.trim()
  // 密码**不** trim：首尾空格可能是密码本身的一部分，擅自裁掉会让正确密码变成错的。
  const password = input.password

  if (username === '' || password === '') {
    return { ok: false, reason: '登录名与密码都要填' }
  }
  return { ok: true, username, password }
}
