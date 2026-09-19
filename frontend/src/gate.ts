/**
 * 登录门禁的输入校验（纯逻辑，便于独立测试）。
 *
 * 为什么先在前端挡一道：后端要求 **两层** 鉴权——`Authorization: Bearer <token>` 证明
 * 「够得着后台」，`X-Operator-Id` 须落在复核人白名单内证明「有权审批」。
 * 缺任一层、或 operator 不是数字（后端解析失败）都是 401/403。
 *
 * 若把空值/非数字直接发出去，用户只会看到一句「鉴权失败」，无从判断是**填错了**
 * 还是**权限不够**——这两种情况的处置完全不同。此函数把「明显的输入错误」提前挡下，
 * 使错误信息能指向具体的那一层。
 */

export interface GateInput {
  token: string
  operatorId: string
}

export type GateCheck =
  | { ok: true; token: string; operatorId: string }
  | { ok: false; reason: string }

export function validateGate(input: GateInput): GateCheck {
  const token = input.token.trim()
  const operatorId = input.operatorId.trim()

  if (token === '' || operatorId === '') {
    return { ok: false, reason: 'API 令牌与操作人 ID 都要填' }
  }
  // 只接受非负整数：后端把 X-Operator-Id 解析为 Telegram userId，
  // `-1`、`4.2`、`42x` 这类送出去也会是 401/403，不如在此说清。
  if (!/^\d+$/.test(operatorId)) {
    return { ok: false, reason: '操作人 ID 应是数字（Telegram userId）' }
  }
  return { ok: true, token, operatorId }
}
