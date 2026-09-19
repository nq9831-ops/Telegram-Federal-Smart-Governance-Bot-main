import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址。开发时由 dev server 反向代理，避免 CORS，也避免「前端源也要被后端信任」这类额外配置。
// 生产环境由 nginx / Ingress 承担同样的职责（见项目根的 README）。
// 后端不在本机 8080 时，改这一行即可（刻意不读 process.env：那会要求引入 @types/node，
// 而这是本工程唯一需要它的地方）。
const backend = 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 审批中心 REST API（模块十一）。与生产反代的路径保持一致。
      '/admin': { target: backend, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    // 内部运营工具，不需要为老浏览器降级
    target: 'es2022',
  },
})
