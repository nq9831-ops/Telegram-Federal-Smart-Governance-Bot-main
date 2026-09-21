/**
 * 渲染探针用的**最小后端替身**（CI 专用，无第三方依赖）。
 *
 * 为什么需要它：`vite preview` 起的是**纯静态**服务，但 `vite.config.ts` 把 `/admin` 代理到
 * `http://127.0.0.1:8080`；CI 的 frontend job 里没有后端 ⇒ 代理 `ECONNREFUSED` ⇒ 浏览器拿到
 * **502** ⇒ `App.vue` 挂载时调 `/admin/auth/login-config` 失败 ⇒ 控制台出现一条 `Failed to load
 * resource: ... 502`。而探针的判据含 `errors.length > 0`（screenshot-probe.mjs），于是**三个主题
 * 场景的渲染判据全过、却整体判失败**——典型的「环境性假阴性」。
 *
 * 修法为什么是**补环境**而不是**放宽判据**：探针存在的理由是「页面真的渲染出来了」，
 * 放宽 `errors` 会连**真实**的资源加载失败（少打包一个 JS、路径写错）一并放过，把门禁变成装饰。
 * 故这里把缺的那一端补上：让 login-config 真的能应答。
 *
 * ⚠️ 返回值刻意是**空 bot username**：
 *   - 与「未配置 Telegram 登录」的生产形态一致；
 *   - 前端据此**不加载** telegram.org 的 widget 脚本 ⇒ 渲染验收不依赖外网（否则 CI 会因
 *     第三方不可达而红/绿不定）。
 *
 * 未实现的端点一律 404：**不要**在这里返回 200 空对象——那会把「前端调了不存在的接口」
 * 这类真问题掩盖掉。
 */
import { createServer } from 'node:http'

const PORT = Number(process.env.STUB_PORT ?? 8080)
const HOST = process.env.STUB_HOST ?? '127.0.0.1'

const routes = {
  // 前端挂载时唯一的必需调用（见 frontend/src/App.vue 的 onMounted）。
  '/admin/auth/login-config': () => ({ telegramBotUsername: '' }),
}

const server = createServer((req, res) => {
  const path = (req.url ?? '').split('?')[0]
  const handler = routes[path]
  if (handler) {
    const body = JSON.stringify(handler())
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' })
    res.end(body)
    return
  }
  res.writeHead(404, { 'content-type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify({ error: `stub 未实现该端点：${path}` }))
})

server.listen(PORT, HOST, () => {
  console.log(`[stub-backend] listening on http://${HOST}:${PORT}（已实现：${Object.keys(routes).join(', ')}）`)
})
