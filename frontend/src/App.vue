<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from './stores/session'
import { validateGate } from './gate'
import { fetchLoginConfig, isMiniAppEnvironment } from './api/client'
import ThemeToggle from './components/ThemeToggle.vue'
import MiniAppCenter from './views/MiniAppCenter.vue'

const session = useSession()
const route = useRoute()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const submitting = ref(false)

/**
 * 是否处于 Telegram Mini App 容器内（拿到 initData 即为真）。
 * 决定门禁页是否显示「用 Telegram 身份登录」这条入口——判据来自容器注入，不是硬编码。
 */
const inMiniApp = ref(isMiniAppEnvironment())

/** 导航：radio 组选中的值即路由路径（`router.ts` 里登记；越权项由守卫回退）。 */
function navigate(path: string | number | boolean | undefined): void {
  void router.push(String(path))
}

async function submit(): Promise<void> {
  // 校验抽在 gate.ts（纯逻辑，可独立测）——这里只负责反馈与落地。
  const check = validateGate(form)
  if (!check.ok) {
    ElMessage.warning(check.reason)
    return
  }
  submitting.value = true
  try {
    await session.signIn(check.username, check.password)
    ElMessage.success('已登录')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败')
  } finally {
    submitting.value = false
  }
}

async function signOut(): Promise<void> {
  await session.signOut()
  form.username = ''
  form.password = ''
}

// ── Telegram 登录（Login Widget）──────────────────────────────────────────────
// bot username 来自后端配置（不硬编码）；未配置时按钮不出现，账号密码登录始终可用。
const tgContainer = ref<HTMLElement | null>(null)

onMounted(async () => {
  // 容器的启动参数（tgWebAppData）可能在首帧之后才可见，故挂载完成后再判一次。
  inMiniApp.value = isMiniAppEnvironment()
  try {
    const cfg = await fetchLoginConfig()
    if (cfg.telegramBotUsername !== '') {
      mountTelegramWidget(cfg.telegramBotUsername)
    }
  } catch {
    // 未启用 / 不可达时静默——账号密码登录仍可用
  }
})

function mountTelegramWidget(botUsername: string): void {
  // widget 通过全局回调把用户数据交回；这里只负责转发给会话（服务端会验签）
  ;(window as unknown as { onTelegramAuth: (user: Record<string, string>) => void }).onTelegramAuth =
    async (user) => {
      try {
        await session.signInWithTelegram(user)
        ElMessage.success('已通过 Telegram 登录')
      } catch (error) {
        ElMessage.error(error instanceof Error ? error.message : 'Telegram 登录失败')
      }
    }
  const script = document.createElement('script')
  script.src = 'https://telegram.org/js/telegram-widget.js?22'
  script.async = true
  script.setAttribute('data-telegram-login', botUsername)
  script.setAttribute('data-size', 'large')
  script.setAttribute('data-onauth', 'onTelegramAuth(user)')
  script.setAttribute('data-request-access', 'write')
  tgContainer.value?.appendChild(script)
}
</script>

<template>
  <!-- 门禁：账号 + 密码登录，成功后持服务端会话令牌 -->
  <div v-if="!session.authenticated" class="gate">
    <el-card class="gate-card pixel-card">
      <template #header>
        <div class="gate-head">
          <h1 class="page-title">TGG 治理后台</h1>
          <ThemeToggle />
        </div>
      </template>

      <!-- Telegram Mini App 容器内：先走 initData 换会话；账号密码表单退为兜底 -->
      <MiniAppCenter v-if="inMiniApp" />

      <el-alert type="info" :closable="false" show-icon title="登录">
        <p class="gate-alert">
          使用<b>后台账号</b>登录（超级管理员由部署方经环境变量引导创建，操作员由超管创建）。
          身份由服务端会话持有，登出 / 停用即时失效。
        </p>
      </el-alert>

      <el-form label-position="top" class="gate-form" @submit.prevent="submit">
        <el-form-item label="登录名">
          <el-input v-model="form.username" placeholder="后台账号登录名" @keyup.enter="submit" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="密码"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" class="gate-submit" :loading="submitting" @click="submit">登录</el-button>
      </el-form>

      <!-- Telegram 登录按钮（由 widget script 注入；未配置 bot username 时为空） -->
      <div ref="tgContainer" class="tg-login"></div>

      <p class="gate-note">
        令牌只保存在本浏览器（localStorage），不会上传到别处。请在受信任的设备上使用。
      </p>
    </el-card>
  </div>

  <template v-else>
    <div class="admin-nav">
      <!-- 导航即路由：地址栏可分享深链、刷新后停在原页（此前条件渲染做不到）。 -->
      <el-radio-group :model-value="route.path" size="small" @change="navigate">
        <el-radio-button value="/todos">待办中心</el-radio-button>
        <el-radio-button value="/config">配置中心</el-radio-button>
        <el-radio-button value="/mine">我的收录</el-radio-button>
        <el-radio-button v-if="session.canReadAudit" value="/audit">审计</el-radio-button>
        <el-radio-button v-if="session.canReadCredit" value="/credit">信用</el-radio-button>
        <el-radio-button v-if="session.role === 'SUPER_ADMIN'" value="/accounts">账号管理</el-radio-button>
      </el-radio-group>
    </div>
    <!-- 视图经 router-view 渲染；仍以 props/emit 传 operator 与登出，既有视图组件无需改动。 -->
    <router-view v-slot="{ Component }">
      <component :is="Component" :operator="session.operatorLabel" @sign-out="signOut" />
    </router-view>
  </template>
</template>

<style scoped>
.gate {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}
.admin-nav {
  display: flex;
  justify-content: center;
  padding: 8px 0;
  background: var(--tgg-surface-0);
  border-bottom: 1px solid var(--tgg-surface-2);
}
.gate-card {
  width: 460px;
}
.gate-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.gate-alert {
  margin: 4px 0 0;
  line-height: 1.6;
}
.gate-form {
  margin-top: 16px;
}
.gate-submit {
  width: 100%;
}
.gate-note {
  margin: 16px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
</style>
