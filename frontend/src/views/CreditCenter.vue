<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import {
  CREDIT_MAX_PAGE_SIZE,
  describeError,
  fetchCreditEvents,
  fetchCreditScores,
} from '../api/client'
import type { CreditEvent, CreditScore, CreditSubjectType } from '../api/types'
import ThemeToggle from '../components/ThemeToggle.vue'

defineProps<{ operator: string }>()
const emit = defineEmits<{ 'sign-out': [] }>()

type Tab = 'events' | 'scores'

const tab = ref<Tab>('events')
const loading = ref(false)
/** 403 = 无信用读权限（非超管且未持 CREDIT_READ）——与「加载失败」分开呈现。 */
const forbidden = ref(false)
const error = ref('')
const total = ref(0)

const events = ref<CreditEvent[]>([])
const scores = ref<CreditScore[]>([])

/** 过滤：类型 'ALL' 表示不过滤；id 需与类型成对（后端对「只给 id」返回 400）。 */
const subjectType = ref<'ALL' | CreditSubjectType>('ALL')
const subjectId = ref('')

const SUBJECT_LABEL: Record<string, string> = {
  INDIVIDUAL: '个人',
  GROUP: '群组',
  MERCHANT: '商家',
}

/**
 * 主体文案：`subjectType` 与 `subjectId` **成对**呈现。
 *
 * 不能只显示 id——三种主体类型的 id 数值空间重叠，单看 id 会串号（与审计页同一坑）。
 */
function subjectText(type: CreditSubjectType | null, id: number): string {
  if (type === null) {
    return `#${id}`
  }
  return `${SUBJECT_LABEL[type] ?? type}#${id}`
}

/** 时间戳人性化：后端给的是 ISO 串，直接展示是给机器看的。 */
function formatTime(value: string): string {
  const t = new Date(value)
  return Number.isNaN(t.getTime()) ? value : t.toLocaleString()
}

/** 组装查询参：只带「有值」的过滤条件。 */
function buildQuery(): { subjectType?: CreditSubjectType; subjectId?: number; size: number } {
  const query: { subjectType?: CreditSubjectType; subjectId?: number; size: number } = {
    size: CREDIT_MAX_PAGE_SIZE,
  }
  if (subjectType.value !== 'ALL') {
    query.subjectType = subjectType.value
  }
  const id = subjectId.value.trim()
  if (id !== '') {
    query.subjectId = Number(id)
  }
  return query
}

async function load(): Promise<void> {
  loading.value = true
  forbidden.value = false
  error.value = ''
  try {
    // 按 id 过滤必须同时给类型（后端同判据）：前端先挡，免得拿一个必 400 的请求去打后端。
    if (subjectId.value.trim() !== '' && subjectType.value === 'ALL') {
      error.value = '按主体 id 过滤时请先选择主体类型（id 在类型间重叠，单给 id 会串号）。'
      return
    }
    const query = buildQuery()
    if (tab.value === 'events') {
      const page = await fetchCreditEvents(query)
      events.value = page.items
      total.value = page.total
    } else {
      const page = await fetchCreditScores(query)
      scores.value = page.items
      total.value = page.total
    }
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

function switchTab(next: Tab): void {
  if (tab.value === next) {
    return
  }
  tab.value = next
  void load()
}

onMounted(load)
</script>

<template>
  <el-container class="shell">
    <el-header class="topbar">
      <h1 class="page-title">信用</h1>
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
        title="信用只读且只追加"
        description="本页不提供任何修改入口：流水由业务链写入、账本由信用服务改动，后台只投影。分值变化为数据库夹取后的实际值。"
      />

      <!-- 无读权限：服务端 403。显示层据此给空态，不去猜。 -->
      <el-empty v-if="forbidden" description="无权限查看信用（需超管或 CREDIT_READ）" />

      <template v-else>
        <div class="tabs">
          <el-button :type="tab === 'events' ? 'primary' : 'default'" @click="switchTab('events')">
            流水
          </el-button>
          <el-button :type="tab === 'scores' ? 'primary' : 'default'" @click="switchTab('scores')">
            账本
          </el-button>
        </div>

        <el-alert
          v-if="error"
          class="block"
          type="error"
          :closable="false"
          show-icon
          title="加载失败"
          :description="error"
        />

        <el-form inline class="filters" @submit.prevent>
          <el-form-item label="主体类型">
            <el-select v-model="subjectType" style="width: 140px" @change="load">
              <el-option label="全部" value="ALL" />
              <el-option label="个人" value="INDIVIDUAL" />
              <el-option label="群组" value="GROUP" />
              <el-option label="商家" value="MERCHANT" />
            </el-select>
          </el-form-item>
          <el-form-item label="主体 id">
            <el-input v-model="subjectId" placeholder="可选" style="width: 170px" @keyup.enter="load" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="loading" @click="load">查询</el-button>
          </el-form-item>
        </el-form>

        <el-card v-if="tab === 'events'" shadow="never">
          <el-table :data="events" row-key="id">
            <el-table-column label="时间" min-width="180">
              <template #default="{ row }">{{ formatTime(row.occurredAt) }}</template>
            </el-table-column>
            <el-table-column label="主体" min-width="160">
              <template #default="{ row }">{{ subjectText(row.subjectType, row.subjectId) }}</template>
            </el-table-column>
            <el-table-column prop="eventType" label="事件" min-width="170" show-overflow-tooltip />
            <el-table-column prop="severity" label="等级" width="90" />
            <el-table-column label="硬红线" width="90">
              <template #default="{ row }">{{ row.hardLine ? '是' : '—' }}</template>
            </el-table-column>
            <el-table-column label="分值变化" min-width="150">
              <template #default="{ row }">
                {{ row.scoreBefore }}→{{ row.scoreAfter }}（{{ row.scoreDelta >= 0 ? '+' : '' }}{{ row.scoreDelta }}）
              </template>
            </el-table-column>
            <el-table-column prop="source" label="来源" min-width="120" show-overflow-tooltip />
            <template #empty>
              <el-empty description="暂无信用流水" />
            </template>
          </el-table>
        </el-card>

        <el-card v-else shadow="never">
          <el-table :data="scores" row-key="id">
            <el-table-column label="时间" min-width="180">
              <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
            </el-table-column>
            <el-table-column label="主体" min-width="160">
              <template #default="{ row }">{{ subjectText(row.subjectType, row.subjectId) }}</template>
            </el-table-column>
            <el-table-column prop="score" label="当前分值" width="120" />
            <template #empty>
              <el-empty description="暂无信用账本" />
            </template>
          </el-table>
        </el-card>

        <p class="total">共 {{ total }} 条</p>
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
.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.filters {
  margin-bottom: 4px;
}
.total {
  margin: 12px 0 0;
  font-size: 12px;
  color: #909399;
}
</style>
