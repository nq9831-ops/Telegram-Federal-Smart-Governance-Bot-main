<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import { AUDIT_MAX_LIMIT, describeError, fetchAuditRecent } from '../api/client'
import type { AuditEntry } from '../api/types'
import ThemeToggle from '../components/ThemeToggle.vue'

defineProps<{ operator: string }>()
const emit = defineEmits<{ 'sign-out': [] }>()

const loading = ref(false)
const items = ref<AuditEntry[]>([])
/** 403 = 无审计读权限（非超管且未持 AUDIT_READ）——与「加载失败」分开呈现。 */
const forbidden = ref(false)
const error = ref('')

/**
 * 主体文案：`actorType` 与 `actorId` **成对**呈现。
 *
 * 不能只显示 id——后台账号 id 与 TG userId 数值空间重叠，单看 id 会串号（本项目已踩过）。
 */
function actorText(row: AuditEntry): string {
  if (row.actorType === null) {
    return '—'
  }
  const label = row.actorType === 'ADMIN_ACCOUNT' ? '后台账号' : 'TG 用户'
  return row.actorId === null ? label : `${label}#${row.actorId}`
}

/** 时间戳人性化：后端给的是 ISO 串，直接展示是给机器看的。 */
function formatTime(value: string): string {
  const t = new Date(value)
  return Number.isNaN(t.getTime()) ? value : t.toLocaleString()
}

async function load(): Promise<void> {
  loading.value = true
  forbidden.value = false
  error.value = ''
  try {
    items.value = await fetchAuditRecent(AUDIT_MAX_LIMIT)
  } catch (e) {
    if (axios.isAxiosError(e) && e.response?.status === 403) {
      forbidden.value = true
    } else {
      error.value = describeError(e)
      ElMessage.error(error.value)
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-container class="shell">
    <el-header class="topbar">
      <h1 class="page-title">审计</h1>
      <div class="topbar-right">
        <span class="who">操作人 <b>{{ operator }}</b></span>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        <ThemeToggle />
        <el-button size="small" @click="emit('sign-out')">退出</el-button>
      </div>
    </el-header>

    <el-main v-loading="loading">
      <el-alert
        v-if="!forbidden"
        class="block"
        type="info"
        :closable="false"
        show-icon
        title="审计只读且只追加"
        description="本页不提供任何删除或修改入口；审计记录永不清理。详情字段由后端写入，不含消息正文。"
      />

      <!-- 无读权限：服务端 403。显示层据此给空态，不去猜。 -->
      <el-empty v-if="forbidden" description="无权限查看审计（需超管或 AUDIT_READ）" />

      <template v-else>
        <el-alert
          v-if="error"
          class="block"
          type="error"
          :closable="false"
          show-icon
          title="加载失败"
          :description="error"
        />

        <el-card shadow="never">
          <el-table :data="items" row-key="id">
            <el-table-column label="时间" min-width="180">
              <template #default="{ row }">{{ formatTime(row.occurredAt) }}</template>
            </el-table-column>
            <el-table-column label="主体" min-width="150">
              <template #default="{ row }">{{ actorText(row) }}</template>
            </el-table-column>
            <el-table-column prop="action" label="动作" min-width="180" show-overflow-tooltip />
            <el-table-column prop="target" label="目标" min-width="200" show-overflow-tooltip />
            <el-table-column label="案件" width="90">
              <template #default="{ row }">
                <span>{{ row.caseId === null ? '—' : `#${row.caseId}` }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="outcome" label="结果" width="110" />
            <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip />
            <template #empty>
              <el-empty description="暂无审计记录" />
            </template>
          </el-table>
        </el-card>
      </template>
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
</style>
