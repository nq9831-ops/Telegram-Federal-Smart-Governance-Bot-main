<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearConfig, describeError, fetchConfig, fetchPermissions, restartSystem, updateConfig, FORBIDDEN_CONFIG } from '../api/client'
import type { ConfigCategory, ConfigItem, WritePermission } from '../api/types'
import ThemeToggle from '../components/ThemeToggle.vue'

defineProps<{ operator: string }>()
const emit = defineEmits<{ 'sign-out': [] }>()

const loading = ref(false)
const items = ref<ConfigItem[]>([])
/** 各行的编辑草稿；未编辑时回落到当前生效值。 */
const drafts = ref<Record<string, string>>({})
/** 写权限名单来源（只读信息）。 */
const writePermission = ref<WritePermission | null>(null)

/**
 * 「现在是谁有权写」——必须显式说出来。
 *
 * 回落复核人名单是**刻意的默认**（开箱即用），但它把「能审批」与「能改配置」绑在一起；
 * 若不在界面上标注，运维会以为两者早已分离。
 */
const writePermissionText = computed(() => {
  const info = writePermission.value
  if (info === null) {
    return '——'
  }
  const from = info.source === 'explicit'
    ? '显式配置 tgg.admin.config-admins'
    : '回落自 TGG_MODERATION_REVIEWERS（能审批的人也能改配置）'
  return `共 ${info.count} 人有权写入；来源：${from}`
})

/** 分类的展示顺序与说明——把「能不能写、要不要重启」直接写在分组标题上。 */
const CATEGORY_ORDER: ConfigCategory[] = ['RUNTIME', 'ASSEMBLY', 'SECRET', 'BOOTSTRAP']
const CATEGORY_LABEL: Record<ConfigCategory, string> = {
  RUNTIME: '运行期参数（标「热生效」的改完立即生效）',
  ASSEMBLY: '装配开关 · 改后需重启生效',
  SECRET: '密钥 / 凭据 · 只读',
  BOOTSTRAP: '引导 / 基础设施 · 只读（应用启动的前提）',
}

const grouped = computed(() =>
  CATEGORY_ORDER.map((category) => ({
    category,
    label: CATEGORY_LABEL[category],
    rows: items.value.filter((item) => item.category === category),
  })).filter((group) => group.rows.length > 0),
)

async function load(): Promise<void> {
  loading.value = true
  try {
    const [configItems, permissions] = await Promise.all([fetchConfig(), fetchPermissions()])
    items.value = configItems
    writePermission.value = permissions
    drafts.value = {}
  } catch (error) {
    ElMessage.error(describeError(error, FORBIDDEN_CONFIG))
  } finally {
    loading.value = false
  }
}

function draftOf(item: ConfigItem): string {
  return drafts.value[item.key] ?? item.effectiveValue
}

function setDraft(item: ConfigItem, value: string): void {
  drafts.value = { ...drafts.value, [item.key]: value }
}

async function save(item: ConfigItem): Promise<void> {
  try {
    await updateConfig(item.key, draftOf(item))
    ElMessage.success(`${item.key} 已保存${item.restartRequired ? '（重启后生效）' : ''}`)
    await load()
  } catch (error) {
    ElMessage.error(describeError(error, FORBIDDEN_CONFIG))
  }
}

async function reset(item: ConfigItem): Promise<void> {
  try {
    await clearConfig(item.key)
    ElMessage.success(`${item.key} 已恢复默认`)
    await load()
  } catch (error) {
    ElMessage.error(describeError(error, FORBIDDEN_CONFIG))
  }
}

/** 重启是不可逆的对外动作（服务会短暂不可用），必须二次确认，并把「需外部监管进程」说清。 */
async function doRestart(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将优雅退出应用进程，由外部监管进程（Docker / systemd / k8s）重新拉起。'
        + '若部署没有监管进程，服务会停掉且不会自行恢复。是否继续？',
      '确认重启服务',
      { type: 'warning', confirmButtonText: '重启', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await restartSystem()
    ElMessage.warning('重启已受理，页面稍后将不可用')
  } catch (error) {
    ElMessage.error(describeError(error, FORBIDDEN_CONFIG))
  }
}

onMounted(load)
</script>

<template>
  <el-container class="shell">
    <el-header class="topbar">
      <h1 class="page-title">配置中心</h1>
      <div class="topbar-right">
        <span class="who">操作人 <b>{{ operator }}</b></span>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        <el-button size="small" type="danger" plain @click="doRestart">重启服务</el-button>
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
        title="密钥与引导态配置只读"
        :description="`为防凭据经 Web 泄漏，密钥类配置在此只回显「已设 / 未设」，不可改写。装配开关的改动需重启后生效。${writePermissionText}`"
      />

      <el-alert
        class="block"
        type="warning"
        :closable="false"
        show-icon
        title="群内自治配置不在此页管理"
        description="违禁词、教学规则、群开关等是「按群」设置（不是键→值），入口在群内命令（/addword、/teach、/enable 等），与「群内事务由群管理员决定」的口径一致。本页只管理平台级配置。"
      />

      <el-card v-for="group in grouped" :key="group.category" shadow="never" class="group">
        <template #header>
          <div class="group-head">
            <span class="group-title">{{ group.label }}</span>
            <span class="group-count">{{ group.rows.length }} 项</span>
          </div>
        </template>

        <el-table :data="group.rows" row-key="key" class="config-table">
          <el-table-column prop="key" label="键" min-width="260" />
          <el-table-column label="生效值" min-width="220">
            <template #default="{ row }">
              <span v-if="row.secret || !row.editable" class="value-readonly">{{ row.effectiveValue }}</span>
              <el-input
                v-else
                size="small"
                :model-value="draftOf(row)"
                @update:model-value="(v: string) => setDraft(row, v)"
              />
            </template>
          </el-table-column>
          <el-table-column label="来源" width="110">
            <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.source }}</el-tag></template>
          </el-table-column>
          <el-table-column label="重启" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.restartRequired" size="small" type="warning">需重启</el-tag>
              <el-tag v-else size="small" type="success">热生效</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="320" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description }}</template>
          </el-table-column>
          <el-table-column label="" width="140" align="right">
            <template #default="{ row }">
              <template v-if="row.editable">
                <el-button link type="primary" @click="save(row)">保存</el-button>
                <el-button link @click="reset(row)">恢复默认</el-button>
              </template>
              <span v-else class="value-readonly">只读</span>
            </template>
          </el-table-column>
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
.group {
  margin-bottom: 16px;
  border-radius: var(--tgg-radius-2xl);
}
.group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.group-title {
  font-weight: 600;
}
.group-count {
  font-size: 13px;
  color: var(--tgg-fg-dim);
}
.value-readonly {
  color: var(--tgg-fg-dim);
  font-family: monospace;
}
</style>
