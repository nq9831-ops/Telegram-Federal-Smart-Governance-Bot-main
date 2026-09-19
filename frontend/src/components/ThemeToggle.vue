<script setup lang="ts">
import { computed } from 'vue'
import { useTheme } from '../stores/theme'

const themeStore = useTheme()
const isDark = computed(() => themeStore.theme === 'dark')
const hint = computed(() => (isDark.value ? '当前：深色（点击切浅色）' : '当前：浅色（点击切深色）'))
</script>

<template>
  <el-tooltip :content="hint" placement="bottom">
    <el-button size="small" circle aria-label="切换主题" @click="themeStore.toggle()">
      <span class="glyph">{{ isDark ? '☾' : '☀' }}</span>
    </el-button>
  </el-tooltip>
</template>

<style scoped>
.glyph {
  font-size: 15px;
  line-height: 1;
}
</style>
