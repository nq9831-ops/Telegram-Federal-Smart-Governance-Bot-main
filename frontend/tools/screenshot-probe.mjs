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
 * pnpm build && pnpm preview &   # 端口/--strictPort 见 package.json（4173 被占则直接失败，避免截到别的应用）
 *
 * # 3) 跑探针（CHROME_PATH 省略时用 playwright 记录的浏览器路径）
 * node tools/screenshot-probe.mjs
 * ```
 *
 * ⚠️ 脚本本体放在 `tools/`（**不是** `scripts/`）：根 `.git/info/exclude` 忽略**任意层级**的
 * 同名目录，`frontend/scripts/` 也会被静默排除出 git。
 *
 * 输出的 JSON 里，`bodyBg`（**计算样式**）是判据最硬的一条：它证明主题变量真的应用到了渲染，
 * 而不只是"CSS 文件里有那行字"。`errors` 与 `appHtmlLength` 用来抓白屏与资源 404。
 * ⚠️ `dataTheme` / `bodyBg` 会被**强制**比对各场景的预期值——否则三个「主题场景」的通过条件
 * 完全恒等，主题变量失效时探针照样绿（该洞由提交后审查抓到）。
 */
import { chromium } from 'playwright-core'

const EXEC = process.env.CHROME_PATH ?? chromium.executablePath()
const URL = process.env.TARGET_URL ?? 'http://localhost:4173/'
const OUT_DIR = process.env.OUT_DIR ?? '/tmp/tgg-shot'

/** 三种场景覆盖「跟随系统」与「显式选择压过系统」两条规则；`expected` 是该场景必须成立的主题。 */
const SCENARIOS = [
  {
    name: '1-dark-system',
    colorScheme: 'dark',
    storedTheme: null,
    expected: { dataTheme: 'dark', bodyBg: 'rgb(11, 11, 20)' },
  },
  {
    name: '2-light-system',
    colorScheme: 'light',
    storedTheme: null,
    expected: { dataTheme: 'light', bodyBg: 'rgb(255, 255, 255)' },
  },
  {
    name: '3-light-system-but-user-chose-dark',
    colorScheme: 'light',
    storedTheme: 'dark',
    expected: { dataTheme: 'dark', bodyBg: 'rgb(11, 11, 20)' },
  },
]

// GitHub Actions 的 Linux runner 上，headless Chrome 需要这两个参数才能启动（沙箱 / 共享内存），
// 否则失败点在「浏览器起不来」、与页面渲染无关。本地不加，保持默认沙箱。
const LAUNCH_ARGS = process.env.CI ? ['--no-sandbox', '--disable-dev-shm-usage'] : []
const browser = await chromium.launch({ executablePath: EXEC, args: LAUNCH_ARGS })

async function probe({ name, colorScheme, storedTheme, expected }) {
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

  return { name, colorScheme, storedTheme, expected, ...evidence, errors }
}

const results = []
for (const scenario of SCENARIOS) {
  results.push(await probe(scenario))
}
await browser.close()

// 这是本 CLI 的**输出本体**，不是调试残留：把证据打到 stdout 供人或 CI 消费。
process.stdout.write(JSON.stringify(results, null, 2) + '\n')

// 非 0 退出码让 CI / 人工都能一眼看出有场景失败。
// 判据必须覆盖**这个探针存在的理由**（主题真的生效），而不只是"没白屏、没报错"——
// 只查 appHtmlLength/errors/hasTitle 会让三个主题场景的通过条件完全相同。
const failures = results.filter(
  (r) =>
    r.appHtmlLength <= 0 ||
    r.errors.length > 0 ||
    r.hasTitle !== true ||
    r.dataTheme !== r.expected.dataTheme ||
    r.bodyBg !== r.expected.bodyBg,
)
if (failures.length > 0) {
  process.stderr.write(
    '\n探针失败（白屏 / console 报错 / 标题未渲染 / 主题与实际不符）：' +
      failures.map((f) => `${f.name} [theme=${f.dataTheme} bg=${f.bodyBg}]`).join('; ') +
      '\n',
  )
  process.exit(1)
}
