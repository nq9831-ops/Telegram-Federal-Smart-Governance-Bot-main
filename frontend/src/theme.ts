/**
 * 主题：**跟随系统 + 可手动切换**（产品方 2026-09-19 选定）。
 *
 * 本文件只放**不依赖 Vue / DOM 的纯逻辑**，便于直接单测（见 `theme.test.ts`）。
 * 真正操作 DOM（写 `data-theme`、落 localStorage）的部分在：
 *   - `index.html` 的内联初始化脚本（首屏前设好，避免闪烁）
 *   - `stores/theme.ts`（运行期切换）
 */

export type Theme = 'light' | 'dark'

/** 主题偏好的持久化键。⚠️ 与 `index.html` 内联脚本里的键**必须一致**。 */
export const THEME_KEY = 'tgg.theme'

/**
 * 决定初始主题。优先级：**用户显式选择 > 系统偏好 > 浅色**。
 *
 * 无法识别的存储值（老版本写的、被人手改的、将来改名的）一律**回落**到系统偏好，
 * 而不是抛错——首屏渲染路径上抛错会白屏，代价远大于「主题猜错一次」。
 */
export function resolveInitialTheme(stored: string | null, prefersDark: boolean): Theme {
  if (stored === 'light' || stored === 'dark') {
    return stored
  }
  return prefersDark ? 'dark' : 'light'
}

/** 两态互切。 */
export function nextTheme(current: Theme): Theme {
  return current === 'dark' ? 'light' : 'dark'
}

/** 系统是否偏好深色。环境不支持 `matchMedia` 时按浅色（保守）。 */
export function systemPrefersDark(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  )
}
