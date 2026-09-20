<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useSession } from './stores/session'
import { validateGate } from './gate'
import ThemeToggle from './components/ThemeToggle.vue'
import TodoCenter from './views/TodoCenter.vue'
import ConfigCenter from './views/ConfigCenter.vue'

const session = useSession()
const form = reactive({ token: '', operatorId: '' })
const submitting = ref(false)
/** 已登录后的两个视图；项目刻意不引 vue-router（只两块，条件渲染足够）。 */
const view = ref<'todos' | 'config'>('todos')

function submit(): void {
  // 校验抽在 gate.ts（纯逻辑，可独立测）——这里只负责反馈与落地。
  const check = validateGate(form)
  if (!check.ok) {
    ElMessage.warning(check.reason)
    return
  }

  submitting.value = true
  session.signIn({ token: check.token, operatorId: check.operatorId })
  submitting.value = false
  ElMessage.success('凭据已保存在本浏览器')
}

function signOut(): void {
  session.signOut()
  form.token = ''
  form.operatorId = ''
}
</script>

<template>
  <!-- 门禁：后端要求 token 与 operator **两层**，缺任一层都是 401/403，故先收凭据再进主界面 -->
  <div v-if="!session.authenticated" class="gate">
    <el-card class="gate-card pixel-card">
      <template #header>
        <div class="gate-head">
          <h1 class="page-title">TGG 治理后台</h1>
          <ThemeToggle />
        </div>
      </template>

      <el-alert type="info" :closable="false" show-icon title="两层鉴权">
        <p class="gate-alert">
          <b>API 令牌</b>证明「够得着后台」（部署方设的 <code>TGG_ADMIN_API_TOKEN</code>）；
          <b>操作人 ID</b>证明「有权审批」，须落在 <code>TGG_MODERATION_REVIEWERS</code> 白名单内。
        </p>
      </el-alert>

      <el-form label-position="top" class="gate-form" @submit.prevent="submit">
        <el-form-item label="API 令牌">
          <el-input
            v-model="form.token"
            type="password"
            show-password
            placeholder="部署方配置的 tgg.admin.api-token"
          />
        </el-form-item>
        <el-form-item label="操作人 ID（Telegram userId）">
          <el-input v-model="form.operatorId" placeholder="例如 1024" />
        </el-form-item>
        <el-button type="primary" class="gate-submit" :loading="submitting" @click="submit">进入</el-button>
      </el-form>

      <p class="gate-note">
        凭据只保存在本浏览器（localStorage），不会上传到别处。请在受信任的设备上使用。
      </p>
    </el-card>
  </div>

  <template v-else>
    <div class="admin-nav">
      <el-radio-group v-model="view" size="small">
        <el-radio-button value="todos">待办中心</el-radio-button>
        <el-radio-button value="config">配置中心</el-radio-button>
      </el-radio-group>
    </div>
    <TodoCenter v-if="view === 'todos'" :operator="session.operator" @sign-out="signOut" />
    <ConfigCenter v-else :operator="session.operator" @sign-out="signOut" />
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
