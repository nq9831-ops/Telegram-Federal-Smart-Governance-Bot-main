/**
 * 渲染验收探针：用**真实 Chromium** 打开前端，采集可复核的证据。
 *
 * 为什么需要它：`pnpm build` 通过 ≠ 页面能看。构建只证明"能打包"，渲染白屏、主题没生效、
 * 首访 404 这类问题它一个都发现不了（本项目就实测到过 favicon 404）。
 * 本探针把"人打开看一眼"变成可重复执行的脚本。
 *
 * ## 用法
 *
 * ```bash
 * # 1) 装依赖（只需 playwright-core，不必下载浏览器——用系统已有的 Chromium 系浏览器）
 * pnpm add -D playwright-core        # 或装在任意目录，本脚本不依赖项目依赖树
 *
 * # 2) 构建并起静态服务
 * pnpm build && pnpm preview --port 4173 &
 *
 * # 3) 跑探针（CHROME_PATH 省略时用 playwright 记录的浏览器路径）
 * node scripts/screenshot-probe.mjs
 * ```
 *
 * 输出的 JSON 里，`bodyBg`（**计算样式**）是判据最硬的一条：它证明主题变量真的应用到了渲染，
 * 而不只是"CSS 文件里有那行字"。`errors` 与 `appHtmlLength` 用来抓白屏与资源 404。
 */
import { chromium } from 'playwright-core'

const EXEC = process.env.CHROME_PATH ?? chromium.executablePath()
const URL = process.env.TARGET_URL ?? 'http://localhost:4173/'
const OUT_DIR = process.env.OUT_DIR ?? '/tmp/tgg-shot'

/** 三种场景覆盖「跟随系统」与「显式选择压过系统」两条规则。 */
const SCENARIOS = [
  { name: '1-dark-system', colorScheme: 'dark', storedTheme: null },
  { name: '2-light-system', colorScheme: 'light', storedTheme: null },
  { name: '3-light-system-but-user-chose-dark', colorScheme: 'light', storedTheme: 'dark' },
]

const browser = await chromium.launch({ executablePath: EXEC })

async function probe({ name, colorScheme, storedTheme }) {
  const context = await browser.newContext({
    colorScheme,
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 2,
  })

  if (storedTheme) {
    // 模拟"用户显式选过"：必须在页面脚本执行前写入
    await context.addInitScript((value) => {
      localStorage.setItem('tgg.theme', value)
    }, storedTheme)
  }

  const page = await context.newPage()
  const errors = []
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text())
  })
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message))

  await page.goto(URL, { waitUntil: 'load' })
  await page.waitForTimeout(600)

  const evidence = await page.evaluate(() => ({
    dataTheme: document.documentElement.getAttribute('data-theme'),
    bodyBg: getComputedStyle(document.body).backgroundColor,
    bodyColor: getComputedStyle(document.body).color,
    appHtmlLength: document.getElementById('app')?.innerHTML.length ?? -1,
    hasTitle: (document.body.innerText || '').includes('TGG 治理后台'),
  }))

  await page.screenshot({ path: `${OUT_DIR}/${name}.png`, fullPage: true })
  await context.close()

  return { name, colorScheme, storedTheme, ...evidence, errors }
}

const results = []
for (const scenario of SCENARIOS) {
  results.push(await probe(scenario))
}
await browser.close()

// 这是本 CLI 的**输出本体**，不是调试残留：把证据打到 stdout 供人或 CI 消费。
process.stdout.write(JSON.stringify(results, null, 2) + '\n')

// 非 0 退出码让 CI / 人工都能一眼看出「有场景失败」：白屏、console 报错、主题没生效。
const failed = results.some(
  (r) => r.appHtmlLength <= 0 || r.errors.length > 0 || r.hasTitle !== true,
)
if (failed) {
  process.stderr.write('\n探针失败：存在白屏 / console 报错 / 标题未渲染的场景。\n')
  process.exit(1)
}
