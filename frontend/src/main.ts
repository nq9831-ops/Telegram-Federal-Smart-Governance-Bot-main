import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import { useTheme } from './stores/theme'
import './styles.css'

// 刻意**不引 vue-router**：目前只有「凭据门禁 + 待办中心」两块，条件渲染即可。
// 等真的出现第三个页面（如历史查询/统计趋势）再引入，那时它才承担路由的职责。
const app = createApp(App).use(createPinia()).use(ElementPlus, { locale: zhCn })

// 系统主题变化时跟随——**只**在用户没有显式选择过时才跟（判定在 store 里，见其注释）。
// 首屏那一次由 index.html 的内联脚本负责（避免闪烁），这里只管运行期。
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
  useTheme().followSystemIfUnset()
})

app.mount('#app')
