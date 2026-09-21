<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchMyListings, describeError } from '../api/client'
import type { MyListing } from '../api/types'
import ThemeToggle from '../components/ThemeToggle.vue'

defineProps<{ operator: string }>()
const emit = defineEmits<{ 'sign-out': [] }>()

const loading = ref(false)
const items = ref<MyListing[]>([])
const error = ref('')

/** 时间戳人性化：后端给的是 ISO 串，直接展示是给机器看的。 */
function formatTime(value: string): string {
  const t = new Date(value)
  return Number.isNaN(t.getTime()) ? value : t.toLocaleString()
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    items.value = await fetchMyListings()
  } catch (e) {
    error.value = describeError(e)
    ElMessage.error(error.value)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-container class="shell">
    <el-header class="topbar">
      <h1 class="page-title">我的收录</h1>
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
        title="只显示你提交的收录"
        description="数据范围 OWN：这里只含当前登录主体自己提交的收录行（后端在查询条件里过滤，不是前端筛）。后台账号没有「自己提交的收录」，故列表恒为空。"
      />

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
          <el-table-column label="编号" width="90">
            <template #default="{ row }">#{{ row.id }}</template>
          </el-table-column>
          <el-table-column prop="chatId" label="群 ID" width="180" />
          <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="130" />
          <el-table-column label="提交时间" min-width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <template #empty>
            <el-empty description="你还没有提交过收录" />
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
</style>
