import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import { router } from './router'
import { useTheme } from './stores/theme'
import './styles.css'

// 路由（2026-09-23 引入）：交易域带来「列表 → 详情 → 争议」三级导航与可分享深链，
// 正是原先注释里写的引入条件（「等真需要深链或嵌套路由时再引入，那时它才真正承担路由职责」）。
// 视图按路由懒加载，首屏不再吃下全部业务视图。
const app = createApp(App).use(createPinia()).use(ElementPlus, { locale: zhCn }).use(router)

// 系统主题变化时跟随——**只**在用户没有显式选择过时才跟（判定在 store 里，见其注释）。
// 首屏那一次由 index.html 的内联脚本负责（避免闪烁），这里只管运行期。
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
  useTheme().followSystemIfUnset()
})

app.mount('#app')
