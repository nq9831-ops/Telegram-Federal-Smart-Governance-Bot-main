<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { describeError, fetchApprovals, fetchConfig, fetchStats, FORBIDDEN_APPROVAL } from '../api/client'
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
/**
 * 加载失败的错误文案——**留在页面上**（不只弹一次 toast）。
 * 只 toast 的话，刷新后页面退化成空表，「真没有待办」与「没拉到」就分不清了。
 * 与审计 / 我的收录同一错误态口径。
 */
const error = ref('')

/**
 * 超时阈值**来自配置中心**（`tgg.admin.overdue-*-hours`，热参数），不硬编码。
 * 否则部署方把阈值改成 6h/12h，界面却仍写「超过 24 小时」——文案对不上实际口径，
 * 是那种「不影响功能、但会误导运营者」的静默谎言。
 */
const remindHours = ref<number | null>(null)
const escalateHours = ref<number | null>(null)

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

/** 风险等级的中文名——与同表的「硬红线 / 待办」保持同一语言，不裸显后端英文枚举。 */
function riskText(level: string): string {
  const names: Record<string, string> = { NONE: '无', LOW: '低', MEDIUM: '中', HIGH: '高' }
  return names[level] ?? level
}

function statusText(value: ReviewStatus): string {
  if (value === 'PENDING') return '待办'
  return value === 'APPROVED' ? '已维持' : '已推翻'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    // 注意 page 是 0 基（后端 `@RequestParam(defaultValue = "0")`），界面上是 1 基
    const [pageData, statsData, config] = await Promise.all([
      fetchApprovals({ status: status.value, page: page.value - 1, size: size.value }),
      fetchStats(),
      fetchConfig(),
    ])
    items.value = pageData.items
    total.value = pageData.total
    stats.value = statsData

    const readHours = (key: string): number | null => {
      const item = config.find((c) => c.key === key)
      const parsed = item ? Number(item.effectiveValue) : Number.NaN
      return Number.isFinite(parsed) ? parsed : null
    }
    remindHours.value = readHours('tgg.admin.overdue-remind-hours')
    escalateHours.value = readHours('tgg.admin.overdue-escalate-hours')
  } catch (e) {
    error.value = describeError(e, FORBIDDEN_APPROVAL)
    ElMessage.error(error.value)
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
      <!-- 加载失败：持久错误提示（对齐审计 / 我的收录）——页面不再退化成无信息的空表。 -->
      <el-alert
        v-if="error"
        class="block"
        type="error"
        :closable="false"
        show-icon
        title="加载失败"
        :description="error"
      />

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
          <div class="card-label">超过 {{ remindHours ?? '—' }} 小时</div>
        </el-card>
        <el-card shadow="never" class="card pixel-card">
          <div class="card-num danger">{{ stats?.overdueEscalate ?? '—' }}</div>
          <div class="card-label">超过 {{ escalateHours ?? '—' }} 小时</div>
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
                {{ riskText(row.riskLevel) }}
              </el-tag>
            </template>
          </el-table-column>
          <!--
            「谁、在哪个群、以前有没有前科」——复核人做判断的最小信息集。
            正文由隐私管道清除、库里本就没有，能给的只有定位用的明文 id（设计如此，见 KNOWN-ISSUES）。
          -->
          <el-table-column label="发布者 / 群" min-width="210">
            <template #default="{ row }">
              <div class="cell-stack">
                <span class="cell-mono">用户 {{ row.userId ?? '—' }}</span>
                <span class="cell-dim">群 {{ row.chatId ?? '—' }}</span>
                <span v-if="row.userHitCount > 0" class="cell-dim">
                  本群累计命中 {{ row.userHitCount }} 次<template v-if="row.userHardLineCount > 0">（硬红线 {{ row.userHardLineCount }}）</template>
                </span>
              </div>
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
.block {
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
/* 一格里叠三行（用户 / 群 / 历史）：比铺成三列更能让「谁在哪个群、有无前科」成一条读下来 */
.cell-stack {
  display: flex;
  flex-direction: column;
  line-height: 1.5;
}
.cell-mono {
  font-family: monospace;
  font-size: 13px;
}
.cell-dim {
  font-size: 12px;
  color: var(--tgg-fg-dim);
}
.clickable :deep(.el-table__row) {
  cursor: pointer;
}
.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
