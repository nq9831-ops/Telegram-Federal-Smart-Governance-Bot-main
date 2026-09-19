<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useSession } from './stores/session'
import TodoCenter from './views/TodoCenter.vue'

const session = useSession()
const form = reactive({ token: '', operatorId: '' })
const submitting = ref(false)

function submit(): void {
  const token = form.token.trim()
  const operatorId = form.operatorId.trim()

  if (token === '' || operatorId === '') {
    ElMessage.warning('API 令牌与操作人 ID 都要填')
    return
  }
  // 后端 X-Operator-Id 解析失败就是 401/403，这里先挡一道，避免"填错了却不知道错在哪"
  if (!/^\d+$/.test(operatorId)) {
    ElMessage.warning('操作人 ID 应是数字（Telegram userId）')
    return
  }

  submitting.value = true
  session.signIn({ token, operatorId })
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
        <h1 class="page-title">TGG 治理后台</h1>
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

  <TodoCenter v-else :operator="session.operator" @sign-out="signOut" />
</template>

<style scoped>
.gate {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}
.gate-card {
  width: 460px;
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
