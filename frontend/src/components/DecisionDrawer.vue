<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { decide, describeError, FORBIDDEN_APPROVAL } from '../api/client'
import type { ApprovalItem, Decision } from '../api/types'

const props = defineProps<{ visible: boolean; item: ApprovalItem | null }>()
const emit = defineEmits<{ 'update:visible': [boolean]; decided: [] }>()

const reason = ref('')
const busy = ref(false)

const isPending = computed(() => props.item?.status === 'PENDING')

/** 风险等级的中文名——避免在详情里裸显后端英文枚举（同表的「硬红线」已是中文）。 */
function riskText(level: string): string {
  const names: Record<string, string> = { NONE: '无', LOW: '低', MEDIUM: '中', HIGH: '高' }
  return names[level] ?? level
}

/** 时间戳人性化：后端给的是 ISO 串，直接展示是给机器看的。 */
function formatTime(value: string | null): string {
  if (value === null || value === '') return '—'
  const t = new Date(value)
  return Number.isNaN(t.getTime()) ? value : t.toLocaleString()
}

// 每次打开都清空理由：理由要针对**这一条**案件写，沿用上一条就是粘滞的错误内容。
watch([() => props.visible, () => props.item?.id], ([visible]) => {
  if (visible) reason.value = ''
})

async function submit(decision: Decision): Promise<void> {
  const item = props.item
  if (item === null) return

  if (decision === 'REJECTED') {
    // 推翻会**解封当事人**——这是立即作用于 Telegram 的不可逆对外动作，必须二次确认
    try {
      await ElMessageBox.confirm(
        `将推翻案件 #${item.id}（判定为误报），并立即解封当事人。是否继续？`,
        '确认推翻',
        { type: 'warning', confirmButtonText: '推翻并解封', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  } else if (item.riskLevel === 'HIGH') {
    // 维持 HIGH 会**追加 24 小时禁言**（见 ModerationReviewDecisionService）——同样是立即作用于
    // 真人的对外动作。此前只给「推翻」加确认，却放过这条更常见的，属风险不对称。
    try {
      await ElMessageBox.confirm(
        `将维持案件 #${item.id} 的违规判定，并对当事人追加 24 小时禁言。是否继续？`,
        '确认维持',
        { type: 'warning', confirmButtonText: '维持并禁言', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }

  busy.value = true
  try {
    const result = await decide(item.id, { decision, reason: reason.value.trim() })
    if (result.result === 'ALREADY_DECIDED') {
      ElMessage.warning('这个案件已经有最终结论了（幂等：本次没有改变任何东西）')
    } else {
      ElMessage.success(decision === 'APPROVED' ? '已维持结论' : '已推翻结论')
    }
    emit('update:visible', false)
    emit('decided')
  } catch (error) {
    ElMessage.error(describeError(error, FORBIDDEN_APPROVAL))
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="visible"
    title="案件详情"
    size="540px"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <template v-if="item">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="编号">{{ item.id }}</el-descriptions-item>
        <el-descriptions-item label="风险">
          <el-tag v-if="item.hardLine" type="danger" effect="dark">硬红线</el-tag>
          <el-tag v-else effect="plain">{{ riskText(item.riskLevel) }}</el-tag>
        </el-descriptions-item>
        <!--
          复核人必须知道「在判谁」：推翻＝立即解封当事人，维持 HIGH＝追加 24h 禁言，
          两个动作都落在具体的人身上。正文没有（隐私管道清了），定位只靠这两个明文 id。
        -->
        <el-descriptions-item label="发布者">
          <a
            v-if="item.userId !== null"
            class="tg-link"
            :href="`tg://user?id=${item.userId}`"
            title="在 Telegram 中打开这个用户"
          >{{ item.userId }}</a>
          <span v-else class="dim">—（频道帖等无发布者）</span>
        </el-descriptions-item>
        <el-descriptions-item label="群 ID">
          <span class="mono">{{ item.chatId ?? '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="本群历史">
          <template v-if="item.userHitCount > 0">
            累计命中 <b>{{ item.userHitCount }}</b> 次<template v-if="item.userHardLineCount > 0">，其中硬红线 <b>{{ item.userHardLineCount }}</b> 次</template>
          </template>
          <span v-else class="dim">本群暂无其他命中记录</span>
        </el-descriptions-item>
        <el-descriptions-item label="命中规则">{{ item.ruleIds || '—' }}</el-descriptions-item>
        <el-descriptions-item label="入队时间">{{ formatTime(item.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="已等待">
          {{ item.ageHours }} 小时
          <el-tag v-if="item.overdue" type="warning" size="small" class="overdue-tag">超时</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="状态">{{ item.status }}</el-descriptions-item>
        <el-descriptions-item v-if="item.decidedAt" label="裁决时间">{{ formatTime(item.decidedAt) }}</el-descriptions-item>
        <el-descriptions-item v-if="item.decidedBy" label="裁决人">{{ item.decidedBy }}</el-descriptions-item>
        <el-descriptions-item v-if="item.note" label="裁决理由">{{ item.note }}</el-descriptions-item>
      </el-descriptions>

      <el-alert
        class="block"
        type="info"
        :closable="false"
        show-icon
        title="队列不含消息正文"
        description="隐私管道已清除正文，本表里本就没有可看的内容。请勿把正文粘贴到裁决理由里——理由会写入审计。"
      />

      <template v-if="isPending">
        <el-input
          v-model="reason"
          class="block"
          type="textarea"
          :rows="3"
          maxlength="255"
          show-word-limit
          placeholder="裁决理由（≤255 字，会写入审计记录）"
        />
        <div class="actions">
          <el-button type="danger" :loading="busy" @click="submit('APPROVED')">维持（确认违规）</el-button>
          <el-button :loading="busy" @click="submit('REJECTED')">推翻（误报，会解封）</el-button>
        </div>
      </template>
      <el-alert v-else class="block" type="success" :closable="false" title="这个案件已经有最终结论了，不能重复裁决" />
    </template>
  </el-drawer>
</template>

<style scoped>
.block {
  margin-top: 16px;
}
.tg-link,
.mono {
  font-family: monospace;
}
.dim {
  color: var(--tgg-fg-dim);
}
.overdue-tag {
  margin-left: 8px;
}
.actions {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}
</style>
