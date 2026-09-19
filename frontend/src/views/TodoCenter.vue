<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { describeError, fetchApprovals, fetchStats } from '../api/client'
import type { ApprovalItem, ApprovalStats, ReviewStatus } from '../api/types'
import DecisionDrawer from '../components/DecisionDrawer.vue'
import ThemeToggle from '../components/ThemeToggle.vue'

defineProps<{ operator: string }>()
const emit = defineEmits<{ 'sign-out': [] }>()

const loading = ref(false)
const items = ref<ApprovalItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref<ReviewStatus>('PENDING')
const stats = ref<ApprovalStats | null>(null)

const drawerVisible = ref(false)
const selected = ref<ApprovalItem | null>(null)

const avgText = computed(() => {
  const hours = stats.value?.avgDecisionHours
  return hours === null || hours === undefined ? '—' : `${hours.toFixed(1)} h`
})

/** 硬红线最优先、其次超时——与后端的排序键是同一套语义，视觉上要能对上。 */
function rowClass({ row }: { row: ApprovalItem }): string {
  if (row.hardLine) return 'row-hardline'
  return row.overdue ? 'row-overdue' : ''
}

/** ⚠️ 后端 `RiskLevel` 只有四级（NONE/LOW/MEDIUM/HIGH），没有 CRITICAL。 */
function riskTagType(level: string): 'info' | 'warning' | 'danger' {
  if (level === 'HIGH') return 'danger'
  if (level === 'MEDIUM') return 'warning'
  return 'info'
}

function statusText(value: ReviewStatus): string {
  if (value === 'PENDING') return '待办'
  return value === 'APPROVED' ? '已维持' : '已推翻'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    // 注意 page 是 0 基（后端 `@RequestParam(defaultValue = "0")`），界面上是 1 基
    const [pageData, statsData] = await Promise.all([
      fetchApprovals({ status: status.value, page: page.value - 1, size: size.value }),
      fetchStats(),
    ])
    items.value = pageData.items
    total.value = pageData.total
    stats.value = statsData
  } catch (error) {
    ElMessage.error(describeError(error))
  } finally {
    loading.value = false
  }
}

function openDetail(row: ApprovalItem): void {
  selected.value = row
  drawerVisible.value = true
}

function onDecided(): void {
  drawerVisible.value = false
  void load()
}

function changeStatus(): void {
  page.value = 1
  void load()
}

onMounted(load)
</script>

<template>
  <el-container class="shell">
    <el-header class="topbar">
      <h1 class="page-title">待办中心</h1>
      <div class="topbar-right">
        <span class="who">操作人 <b>{{ operator }}</b></span>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        <ThemeToggle />
        <el-button size="small" @click="emit('sign-out')">退出</el-button>
      </div>
    </el-header>

    <el-main>
      <div class="cards">
        <el-card shadow="never" class="card pixel-card">
          <div class="card-num">{{ stats?.pending ?? '—' }}</div>
          <div class="card-label">待办</div>
        </el-card>
        <el-card shadow="never" class="card pixel-card">
          <div class="card-num danger">{{ stats?.hardLinePending ?? '—' }}</div>
          <div class="card-label">其中硬红线</div>
        </el-card>
        <el-card shadow="never" class="card pixel-card">
          <div class="card-num warn">{{ stats?.overdueRemind ?? '—' }}</div>
          <div class="card-label">超过 24 小时</div>
        </el-card>
        <el-card shadow="never" class="card pixel-card">
          <div class="card-num danger">{{ stats?.overdueEscalate ?? '—' }}</div>
          <div class="card-label">超过 72 小时</div>
        </el-card>
        <el-card shadow="never" class="card pixel-card">
          <div class="card-num">{{ avgText }}</div>
          <div class="card-label">平均裁决耗时</div>
        </el-card>
      </div>

      <el-card shadow="never">
        <template #header>
          <div class="table-head">
            <span class="hint">
              排序：<b>硬红线 → 风险等级 → 先入先审</b>（由后端给出，界面不重排）
            </span>
            <el-radio-group v-model="status" size="small" @change="changeStatus">
              <el-radio-button value="PENDING">待办</el-radio-button>
              <el-radio-button value="APPROVED">已维持</el-radio-button>
              <el-radio-button value="REJECTED">已推翻</el-radio-button>
            </el-radio-group>
          </div>
        </template>

        <el-table
          v-loading="loading"
          :data="items"
          :row-class-name="rowClass"
          class="clickable"
          @row-click="openDetail"
        >
          <el-table-column prop="id" label="编号" width="90" />
          <el-table-column label="等级" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.hardLine" type="danger" effect="dark">硬红线</el-tag>
              <el-tag v-else :type="riskTagType(row.riskLevel)" effect="plain">
                {{ row.riskLevel }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="ruleIds" label="命中规则" min-width="200" show-overflow-tooltip />
          <el-table-column label="已等待" width="110">
            <template #default="{ row }">{{ row.ageHours }} 小时</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">{{ statusText(row.status) }}</template>
          </el-table-column>
          <el-table-column label="" width="130" align="right">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="openDetail(row)">查看 / 裁决</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="没有符合条件的案件" />
          </template>
        </el-table>

        <el-pagination
          class="pager"
          layout="total, prev, pager, next"
          :total="total"
          :page-size="size"
          :current-page="page"
          @current-change="(p: number) => { page = p; load() }"
        />
      </el-card>

      <DecisionDrawer v-model:visible="drawerVisible" :item="selected" @decided="onDecided" />
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
.cards {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.card-num {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
}
.card-num.danger {
  color: var(--tgg-danger);
}
.card-num.warn {
  color: var(--tgg-amber);
}
.card-num.primary {
  color: var(--tgg-primary);
}
.card-label {
  margin-top: 4px;
  font-size: 13px;
  color: var(--tgg-fg-dim);
}
.table-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.hint {
  font-size: 13px;
  color: var(--tgg-fg-silver);
}
.clickable :deep(.el-table__row) {
  cursor: pointer;
}
.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
