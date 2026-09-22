<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ALL_PERMISSIONS,
  PERMISSION_LABEL,
  createAccount,
  describeError,
  fetchAccounts,
  resetAccountPassword,
  revokeAccountSessions,
  setAccountPermissions,
  setAccountStatus,
} from '../api/client'
import type { AccountView } from '../api/types'
import ThemeToggle from '../components/ThemeToggle.vue'

/**
 * 账号管理（仅超管可见/可用）。
 *
 * 超管天然全权，其能力不在此页编辑（后端亦拒绝改动超管）——故超管行的操作按钮禁用。
 * 「谁能做账号管理」由后端把关（非超管一律 403），本页只做呈现。
 */
defineProps<{ operator: string }>()
const emit = defineEmits<{ 'sign-out': [] }>()

const loading = ref(false)
const accounts = ref<AccountView[]>([])
const createForm = reactive({ username: '', password: '' })
/** 各行能力的编辑草稿；点「保存权限」才提交。 */
const permDraft = ref<Record<number, string[]>>({})

async function load(): Promise<void> {
  loading.value = true
  try {
    accounts.value = await fetchAccounts()
    const draft: Record<number, string[]> = {}
    for (const a of accounts.value) {
      draft[a.id] = [...a.permissions]
    }
    permDraft.value = draft
  } catch (error) {
    ElMessage.error(describeError(error, '无权限（403）：账号管理仅超级管理员可用。'))
  } finally {
    loading.value = false
  }
}

async function create(): Promise<void> {
  if (createForm.username.trim() === '' || createForm.password === '') {
    ElMessage.warning('登录名与密码都要填')
    return
  }
  try {
    await createAccount(createForm.username.trim(), createForm.password)
    ElMessage.success('操作员已创建')
    createForm.username = ''
    createForm.password = ''
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

async function savePerms(account: AccountView): Promise<void> {
  try {
    await setAccountPermissions(account.id, permDraft.value[account.id] ?? [])
    ElMessage.success('权限已更新')
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

async function toggleStatus(account: AccountView): Promise<void> {
  const next = account.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  // 停用会**立即吊销该账号的会话**——是不可逆的对外动作，必须二次确认（与重置密码、强制下线同口径）。
  // 启用不弹确认：确认泛滥会让人对确认框脱敏，反而放过真正危险的那一步。
  if (next === 'DISABLED') {
    try {
      await ElMessageBox.confirm(
        `停用「${account.username}」会立即吊销其全部会话，该账号将无法登录。是否继续？`,
        '确认停用账号',
        { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  try {
    await setAccountStatus(account.id, next)
    ElMessage.success(next === 'DISABLED' ? '已停用（其会话已吊销）' : '已启用')
    await load()
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

async function resetPw(account: AccountView): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      `为「${account.username}」设置新密码（保存后这个账号会被强制下线）`,
      '重置密码',
      { inputType: 'password', confirmButtonText: '重置', cancelButtonText: '取消' },
    )
    await resetAccountPassword(account.id, value)
    ElMessage.success('密码已重置（这个账号已被强制下线）')
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(describeError(error))
    }
  }
}

async function revoke(account: AccountView): Promise<void> {
  // 强制下线同样是「立即生效、影响真人」的动作，成对确认（取消即返回，不误发请求）。
  try {
    await ElMessageBox.confirm(
      `强制下线会立即吊销「${account.username}」的全部会话，对方需重新登录才能继续使用。是否继续？`,
      '确认强制下线',
      { type: 'warning', confirmButtonText: '强制下线', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await revokeAccountSessions(account.id)
    ElMessage.success('已强制下线')
  } catch (error) {
    ElMessage.error(describeError(error))
  }
}

onMounted(load)
</script>

<template>
  <el-container class="shell">
    <el-header class="topbar">
      <h1 class="page-title">账号管理</h1>
      <div class="topbar-right">
        <span class="who">操作人 <b>{{ operator }}</b></span>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        <ThemeToggle />
        <el-button size="small" @click="emit('sign-out')">退出</el-button>
      </div>
    </el-header>

    <el-main v-loading="loading">
      <el-alert
        class="block"
        type="info"
        :closable="false"
        show-icon
        title="仅超级管理员可管理账号"
        description="超级管理员天然拥有全部能力，不受能力清单限制；操作员的能力由超管在此分配。停用 / 改密会立即吊销这个账号的会话。"
      />

      <el-card shadow="never" class="block">
        <template #header><span class="card-title">新建操作员</span></template>
        <el-form :inline="true" @submit.prevent="create">
          <el-form-item label="登录名">
            <el-input v-model="createForm.username" placeholder="操作员登录名" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="createForm.password" type="password" show-password placeholder="初始密码" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="create">创建</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card shadow="never">
        <template #header><span class="card-title">账号与能力</span></template>
        <el-table :data="accounts" row-key="id">
          <el-table-column prop="username" label="登录名" min-width="160" />
          <el-table-column label="角色" width="130">
            <template #default="{ row }">
              <el-tag :type="row.role === 'SUPER_ADMIN' ? 'danger' : 'info'" effect="plain">
                {{ row.role === 'SUPER_ADMIN' ? '超级管理员' : '操作员' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'warning'" effect="plain">
                {{ row.status === 'ACTIVE' ? '正常' : '已停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="能力" min-width="360">
            <template #default="{ row }">
              <span v-if="row.role === 'SUPER_ADMIN'" class="hint">全部（超管天然全权）</span>
              <el-select
                v-else
                v-model="permDraft[row.id]"
                multiple
                collapse-tags
                size="small"
                placeholder="选择能力"
                class="perm-select"
              >
                <el-option
                  v-for="p in ALL_PERMISSIONS"
                  :key="p"
                  :label="PERMISSION_LABEL[p] ?? p"
                  :value="p"
                />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="320" align="right">
            <template #default="{ row }">
              <template v-if="row.role !== 'SUPER_ADMIN'">
                <el-button link type="primary" @click="savePerms(row)">保存权限</el-button>
                <el-button link @click="toggleStatus(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button>
                <el-button link @click="resetPw(row)">重置密码</el-button>
                <el-button link type="danger" @click="revoke(row)">强制下线</el-button>
              </template>
              <span v-else class="hint">—</span>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无账号" />
          </template>
        </el-table>
      </el-card>
    </el-main>
  </el-container>
</template>

<style scoped>
.shell {
  height: 100%;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--tgg-surface-1);
  border-bottom: 1px solid var(--tgg-surface-2);
}
.topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.who {
  font-size: 13px;
  color: var(--tgg-fg-silver);
}
.block {
  margin-bottom: 16px;
}
.card-title {
  font-weight: 600;
}
.perm-select {
  width: 100%;
}
.hint {
  font-size: 13px;
  color: var(--tgg-fg-dim);
}
</style>
