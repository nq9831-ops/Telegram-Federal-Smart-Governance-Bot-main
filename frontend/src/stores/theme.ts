import { defineStore } from 'pinia'
import { ref } from 'vue'
import { THEME_KEY, nextTheme, resolveInitialTheme, systemPrefersDark } from '../theme'
import type { Theme } from '../theme'

/**
 * 主题状态：**跟随系统 + 可手动切换**，与 `index.html` 的内联首屏脚本共用同一套规则
 * （`src/theme.ts` 的 `resolveInitialTheme`），三处必须一致，改一处就要改另两处。
 */

/** 读 <html data-theme>；正常路径下 index.html 的内联脚本已经写过，这里是兜底。 */
function currentFromDom(): Theme {
  return resolveInitialTheme(document.documentElement.getAttribute('data-theme'), systemPrefersDark())
}

function readStored(): string | null {
  try {
    return localStorage.getItem(THEME_KEY)
  } catch {
    // 隐私模式等：读不到就当作"没选过"
    return null
  }
}

export const useTheme = defineStore('theme', () => {
  const theme = ref<Theme>(currentFromDom())

  function apply(next: Theme, persist: boolean): void {
    theme.value = next
    document.documentElement.setAttribute('data-theme', next)
    if (!persist) {
      return
    }
    try {
      localStorage.setItem(THEME_KEY, next)
    } catch {
      // 写不进去（隐私模式）：本次会话内仍然生效，只是刷新后回到系统偏好
    }
  }

  /** 用户点了切换：这是**显式选择**，要落盘。 */
  function toggle(): void {
    apply(nextTheme(theme.value), true)
  }

  /**
   * 系统主题变了（如夜间自动切换）。
   *
   * 只有用户**没有显式选择过**时才跟随——否则「用户手动选了浅色、系统傍晚转深色」
   * 会把他的选择悄悄冲掉，那是典型的"设置自己变了"的恼人体验。
   * 跟随系统时**不写 localStorage**：保持"未设置"状态，下一次启动仍跟随系统。
   */
  function followSystemIfUnset(): void {
    if (readStored() !== null) {
      return
    }
    apply(systemPrefersDark() ? 'dark' : 'light', false)
  }

  return { theme, toggle, followSystemIfUnset }
})
