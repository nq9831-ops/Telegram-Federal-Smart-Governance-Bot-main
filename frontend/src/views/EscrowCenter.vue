<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { describeError, fetchEscrowOrder, fetchEscrowOrders, type EscrowOrderView } from '../api/client'

/**
 * 担保交易订单可见面（模块十二 · gap-ESC-05）。
 *
 * <p><b>它解决什么</b>：订单此前只能写、看不到——运营者与联邦裁决方没有界面查看超时单与争议单，
 * 单子会一直躺着。本页提供列表（可按状态筛）+ 详情。
 *
 * <p><b>只读</b>：本页不做任何状态推进（裁定走 Bot 命令与后续的裁决界面），
 * 与后端 `EscrowQueryController` 的只读口径一致。
 *
 * <p><b>状态中文名由后端给</b>（`stateLabel`）：前端不维护第二份状态表——
 * 否则后端新增状态时，这里会继续显示旧文案而无人发现。
 */
defineProps<{ operator: string }>()
const emit = defineEmits<{ (e: 'sign-out'): void }>()

/** 筛选项（value 与后端枚举一致；文案沿用同一套中文名）。 */
const STATE_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'OPEN', label: '待卖方确认' },
  { value: 'CONFIRMED', label: '待托管资金' },
  { value: 'LOCKED', label: '资金托管中' },
  { value: 'DELIVERED', label: '已交付待验收' },
  { value: 'DISPUTED', label: '争议中' },
  { value: 'RELEASED', label: '已完成' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'CANCELLED', label: '已取消' },
]

const orders = ref<EscrowOrderView[]>([])
const total = ref(0)
const loading = ref(false)
const stateFilter = ref('')
const detail = ref<EscrowOrderView | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await fetchEscrowOrders({ state: stateFilter.value || undefined })
    orders.value = page.items
    total.value = page.total
  } catch (error) {
    ElMessage.error(describeError(error, '无权查看担保订单（需超管或联邦管理员）。'))
  } finally {
    loading.value = false
  }
}

async function openDetail(id: number): Promise<void> {
  try {
    detail.value = await fetchEscrowOrder(id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '读取订单详情失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="center">
    <div class="head">
      <h2 class="page-title">担保交易</h2>
      <div class="head-right">
        <span class="operator">{{ operator }}</span>
        <el-button size="small" @click="emit('sign-out')">退出登录</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" show-icon class="block">
      <p class="hint">
        这里只**查看**订单，不改动状态——资金动作（托管 / 放款 / 退款）由当事人在群内用
        <code>/escrow</code> 执行，争议由联邦裁决。状态为「争议中」或长时间停留的单子需要处理。
      </p>
    </el-alert>

    <div class="toolbar">
      <el-select v-model="stateFilter" size="small" class="filter" @change="load">
        <el-option
          v-for="option in STATE_OPTIONS"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
      <span class="count">共 {{ total }} 笔</span>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="orders" size="small" @row-click="(row: EscrowOrderView) => openDetail(row.id)">
      <el-table-column prop="id" label="编号" width="90" />
      <el-table-column prop="stateLabel" label="状态" width="140" />
      <el-table-column label="金额" width="150">
        <template #default="{ row }">{{ row.amount }} {{ row.currency }}</template>
      </el-table-column>
      <el-table-column prop="buyerUserId" label="买家" width="120" />
      <el-table-column prop="sellerUserId" label="卖家" width="120" />
      <el-table-column prop="reason" label="理由" show-overflow-tooltip />
      <template #empty>
        <el-empty description="没有符合条件的订单" />
      </template>
    </el-table>

    <el-drawer
      :model-value="detail !== null"
      :title="detail ? `订单 #${detail.id}` : ''"
      size="420px"
      @close="detail = null"
    >
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="状态">{{ detail.stateLabel }}</el-descriptions-item>
          <el-descriptions-item label="金额">
            {{ detail.amount }} {{ detail.currency }}
          </el-descriptions-item>
          <el-descriptions-item label="买家">{{ detail.buyerUserId }}</el-descriptions-item>
          <el-descriptions-item label="卖家">{{ detail.sellerUserId }}</el-descriptions-item>
          <el-descriptions-item label="理由">{{ detail.reason || '—' }}</el-descriptions-item>
          <el-descriptions-item label="创建">{{ detail.createdAt || '—' }}</el-descriptions-item>
          <el-descriptions-item label="更新">{{ detail.updatedAt || '—' }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.center {
  padding: 16px 24px;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.head-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.operator {
  font-size: 13px;
  color: #909399;
}
.block {
  margin-bottom: 12px;
}
.hint {
  margin: 0;
  line-height: 1.6;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.filter {
  width: 180px;
}
.count {
  font-size: 13px;
  color: #909399;
}
</style>
