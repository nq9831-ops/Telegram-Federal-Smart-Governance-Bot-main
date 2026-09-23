import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import { useTheme } from './stores/theme'
import './styles.css'

// 刻意**不引 vue-router**：当前是「凭据门禁 + 六个导航视图」，全在**同一会话内整页切换**，
// 条件渲染（`App.vue` 的联合类型 `view`）即可承担；router 的 URL/历史能力尚未被需要。
// 等真需要深链（如「某条待办」可分享 URL）或嵌套路由时再引入，那时它才真正承担路由职责。
const app = createApp(App).use(createPinia()).use(ElementPlus, { locale: zhCn })

// 系统主题变化时跟随——**只**在用户没有显式选择过时才跟（判定在 store 里，见其注释）。
// 首屏那一次由 index.html 的内联脚本负责（避免闪烁），这里只管运行期。
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
  useTheme().followSystemIfUnset()
})

app.mount('#app')
