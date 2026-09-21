<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useSession } from './stores/session'
import { validateGate } from './gate'
import ThemeToggle from './components/ThemeToggle.vue'
import TodoCenter from './views/TodoCenter.vue'
import ConfigCenter from './views/ConfigCenter.vue'
import AccountCenter from './views/AccountCenter.vue'

const session = useSession()
const form = reactive({ username: '', password: '' })
/** 已登录后的三个视图；项目刻意不引 vue-router（只三块，条件渲染足够）。账号管理仅超管可见。 */
const view = ref<'todos' | 'config' | 'accounts'>('todos')
const submitting = ref(false)

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

      <p class="gate-note">
        令牌只保存在本浏览器（localStorage），不会上传到别处。请在受信任的设备上使用。
      </p>
    </el-card>
  </div>

  <template v-else>
    <div class="admin-nav">
      <el-radio-group v-model="view" size="small">
        <el-radio-button value="todos">待办中心</el-radio-button>
        <el-radio-button value="config">配置中心</el-radio-button>
        <el-radio-button v-if="session.role === 'SUPER_ADMIN'" value="accounts">账号管理</el-radio-button>
      </el-radio-group>
    </div>
    <TodoCenter v-if="view === 'todos'" :operator="session.operatorLabel" @sign-out="signOut" />
    <ConfigCenter v-else-if="view === 'config'" :operator="session.operatorLabel" @sign-out="signOut" />
    <AccountCenter v-else :operator="session.operatorLabel" @sign-out="signOut" />
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
