<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { describeError, readMiniAppInitData } from '../api/client'
import { useSession } from '../stores/session'

/**
 * Telegram Mini App 壳（模块十二 · gap-ESC-04）。
 *
 * <p><b>职责只有一件</b>：在 Mini App 容器内把 `initData` 换成后台会话，让 TG 用户不必看到账号密码表单。
 * 验签在服务端（`HMAC_SHA256(key=WebAppData)`，与 Login Widget 的 `SHA256(bot_token)` 相反），
 * 本组件只搬运原始串、并把结果如实呈现——不猜失败成因，错误文案一律取后端 `{error}`。
 *
 * <p><b>刻意不做的事</b>：不引 `@telegram-apps/sdk` / `@tonconnect/sdk`（报告 §4.2 N1——真机不可验证，
 * 不把验不了的依赖写进依赖树），不为 Mini App 引 vue-router（`main.ts` 既有口径：条件渲染即可）。
 *
 * <p>⚠️ 未在真实环境验证：无真机 initData、无 Telegram WebView。这里验的是「拿到 initData 之后的接线」。
 */
type Phase = 'probing' | 'signing' | 'signed' | 'failed'

const session = useSession()
const phase = ref<Phase>('probing')
const error = ref('')
/** 拿不到 initData＝不在 Mini App 容器内——此时不给「重试」，因为重试也不会变。 */
const hasInitData = ref(false)

/**
 * initData → 会话令牌。
 *
 * 每次调用都**重新读** initData：容器注入的启动参数在脚本加载后才可见，
 * 挂载那一刻为空不代表之后仍为空（故保留手动的重新登录入口）。
 */
async function exchange(): Promise<void> {
  const initData = readMiniAppInitData()
  hasInitData.value = initData !== ''
  if (!hasInitData.value) {
    phase.value = 'failed'
    error.value = '拿不到 initData：当前不是在 Telegram Mini App 容器内打开的。'
    return
  }
  phase.value = 'signing'
  error.value = ''
  try {
    await session.signInWithMiniApp(initData)
    phase.value = 'signed'
  } catch (e) {
    phase.value = 'failed'
    error.value = describeError(e)
  }
}

onMounted(() => {
  // 已持有会话（如刷新页面时 localStorage 里还有令牌）就不必再换一次。
  if (session.authenticated) {
    hasInitData.value = readMiniAppInitData() !== ''
    phase.value = 'signed'
    return
  }
  void exchange()
})
</script>

<template>
  <div class="miniapp">
    <el-alert
      v-if="phase === 'probing' || phase === 'signing'"
      class="block"
      type="info"
      :closable="false"
      show-icon
      title="正在用 Telegram 身份登录"
      description="已取得 initData，交给服务端验签后换取后台会话。"
    />

    <el-alert
      v-else-if="phase === 'failed'"
      class="block"
      type="error"
      :closable="false"
      show-icon
      title="未能用 Telegram 身份登录"
      :description="error"
    />

    <el-alert
      v-else
      class="block"
      type="success"
      :closable="false"
      show-icon
      title="已通过 Telegram 身份登录"
      description="会话由服务端签发，正在进入后台。"
    />

    <div class="actions">
      <el-button
        v-if="hasInitData && phase !== 'signed'"
        size="small"
        :loading="phase === 'signing'"
        @click="exchange"
      >
        重新用 Telegram 身份登录
      </el-button>
      <span class="hint">
        本入口只在 Telegram Mini App 容器内可用；浏览器里请用下方的后台账号登录。
      </span>
    </div>
  </div>
</template>

<style scoped>
.miniapp {
  margin-bottom: 16px;
}
.block {
  margin-bottom: 8px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.hint {
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
</style>
