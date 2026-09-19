import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import './styles.css'

// 刻意**不引 vue-router**：第一版只有「凭据门禁 + 待办中心」两块，用条件渲染即可。
// 等真的出现第三个页面（如历史查询/统计趋势）再引入，那时它才承担路由的职责。
createApp(App).use(createPinia()).use(ElementPlus, { locale: zhCn }).mount('#app')
