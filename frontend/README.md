# TGG 治理后台控制台（`frontend/`）

模块十一「审批中心」的前端，对应原文 §12.2 的「Vue 3 前端」。

## 技术栈

| 项 | 取值 | 说明 |
|---|---|---|
| 构建 | Vite 8 | — |
| 框架 | Vue 3.5（`<script setup>`） | — |
| 语言 | TypeScript **5.9** | ⚠️ **必须停在 5.x**：TypeScript 7 移除了 `./lib/tsc` 导出，而 `vue-tsc` 3.3 仍按旧路径解析 → 实测直接崩（`ERR_PACKAGE_PATH_NOT_EXPORTED`）。这与后端「Spring Boot 不可升 4.x」是同一类问题 |
| UI | Element Plus 2.14（中文 locale） | 表格 / 分页 / 抽屉开箱即用 |
| 状态 | Pinia 4 | **只**用于「凭据 ⇄ 登录态」的响应式切换 |
| HTTP | axios 1.x | 拦截器统一补两层鉴权头 |

**刻意不引 vue-router**：第一版只有「凭据门禁 + 待办中心」两块，条件渲染就够；等真出现第三个页面再引入。

## 主题（跟随系统 + 可手动切换）

产品方 2026-09-19 选定：**跟随系统 + 可手动切换**。

- **规则只有一份**：`src/theme.ts` 的 `resolveInitialTheme`（**显式选择 > 系统偏好 > 浅色**）。
  它被三处复用——`index.html` 的内联首屏脚本、`stores/theme.ts`、单测。**改一处必须改另两处**。
- **首屏不闪**：`index.html` 里的**同步内联脚本**在首次绘制前把 `data-theme` 写到 `<html>`。
  ⚠️ 它必须是普通 `<script>`——加 `defer` 或改成 `type="module"` 都会在绘制之后才跑，深色用户会先看到一帧白底。
- **运行期跟随**：`main.ts` 监听 `prefers-color-scheme` 变化，但**只在用户没显式选过时**才跟——
  否则「用户手动选了浅色、系统傍晚转深色」会把他的选择悄悄冲掉。跟随系统时**不写** localStorage，
  保持"未设置"状态，下次启动仍跟随系统。
- **两套 token 都是参考站实测值**：深色 `#0b0b14 / #161625 / #1f1f33 / #2e2e44`，
  浅色 `#fff / #f4f6fa / #e8edf4 / #d2dae5`；主色深色 `#4dd0e1`、浅色 `#0e7c8a`。
  像素硬阴影也分两套（深色 `4px 4px 0 #000`、浅色 `4px 4px 0 #131a2829`）。
  Element Plus 的 `--el-*` 全部**引用** `--tgg-*`，因此自动跟随主题，无需第二份配置。

## 测试

```bash
pnpm test          # vitest
pnpm test:watch
```

当前覆盖三类、共 **25** 个用例：

- `src/theme.ts` —— 主题解析纯逻辑（未选过 / 选过 / 存储值非法 / 互切可逆）；
- `src/api/client.ts` —— 凭据存取、请求拦截器补两层鉴权头、响应拦截器 401 清凭据、`describeError` 六分支；
- `src/components/DecisionDrawer.vue` —— **裁决路径**：推翻时取消二次确认**绝不发请求**、确认才发；
  维持不弹框；`ALREADY_DECIDED` 不谎报成功。这是全前端唯一有**不可逆对外后果**的地方（推翻＝立即解封当事人），
  所以判据成对断言——只测「确认后发了请求」是测不出二次确认的（把 confirm 整个删掉它照样绿）。

⚠️ 组件测试需要 DOM：该文件用 `// @vitest-environment jsdom` **逐文件**开启（另两个是纯逻辑，留在默认 node 环境）。
依赖 `@vue/test-utils` + `jsdom`（devDependencies）。

## 渲染验收（`pnpm build` 通过 ≠ 页面能看）

构建只证明「能打包」——白屏、主题没生效、首访 404 它一个都发现不了。用探针补上：

```bash
pnpm build
pnpm preview --port 4173 &                     # 或任意静态服务器
CHROME_PATH="<chromium 系浏览器可执行文件>" node tools/screenshot-probe.mjs
```

探针用**真实 Chromium**，对三个场景各出一张截图（`/tmp/tgg-shot/`）并打印 JSON：
系统深色 / 系统浅色 / **系统浅色但用户显式选了深色**。

**判据看 JSON，不要只看截图**：

| 字段 | 说明 |
|---|---|
| `bodyBg` | `body` 的**计算**背景色——最硬的一条：证明主题变量真的应用到渲染，而不是"CSS 里写了那行字"。深色应为 `rgb(11, 11, 20)`、浅色 `rgb(255, 255, 255)` |
| `errors` | console 错误（白屏与资源 404 都落这里） |
| `appHtmlLength` / `hasTitle` | 是否真渲染出门禁页而非白屏 |

任一场景失败 → 脚本以**非 0 退出码**结束，便于接进 CI。
`CHROME_PATH` 省略时用 playwright 记录的浏览器路径；本机已装 Edge/Chrome 也可直接指向。

> 这个探针抓到过一次真实的 `/favicon.ico` 404——构建与单测都发现不了。

## 开发

```bash
pnpm install
pnpm dev        # http://localhost:5173 —— /admin 由 vite 代理到 http://127.0.0.1:8080
```

后端不在本机 8080 时，改 `vite.config.ts` 里的 `backend` 常量。

## 构建与部署（独立静态站点 + 反向代理）

```bash
pnpm build      # 先 vue-tsc --noEmit 类型检查，再 vite build → dist/
```

把 `dist/` 的内容交给 nginx / Ingress 托管，并把 `/admin` 反代到后端：

```nginx
server {
    listen 443 ssl;
    server_name admin.example.com;
    root /srv/tgg-admin-console;          # dist/ 的内容

    # SPA 回退（本版只有一个视图，保留以免后续加页面时踩 404）
    location / { try_files $uri $uri/ /index.html; }

    # 审批中心 API → 后端
    location /admin/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

⚠️ **`/admin` 不要裸露公网**：至少加来源 IP 白名单，或只在内网 Ingress 上开放。
K8s 下的写法见 `../k8s/app.yaml` 里 `/admin` 那段注释。

## 安全边界（必读）

- **凭据存在 localStorage**：`Authorization: Bearer <token>` + `X-Operator-Id: <userId>`。
  token 是**共享密钥**，放 localStorage 意味着 XSS 可窃取。因此本工程**不渲染任何 HTML/富文本**
  （全仓无 `v-html`），也不做「记住我」之外的额外持久化。
  需要更强保证（如每次手输 operator、或改会话 Cookie）属产品决策，尚未做。
- 页面带 `<meta name="robots" content="noindex, nofollow">`——内部运营工具，不应被搜索引擎收录。
- 裁决里的**「推翻」会立即解封当事人**（不可逆的对外动作），故有二次确认；「维持」没有。

## 已知限制

- **渲染已验，观感仍待人工看图**：`tools/screenshot-probe.mjs` 用**真实 Chromium** 验过三场景
  （主题生效、非白屏、console 无错误，见上「渲染验收」）。但探针给的是**数字判据**——
  排版与配色**好不好看**仍须人看截图确认（截图归档在 `.rivet/artifacts/frontend-render/`，本地资产、不进 git）。
- **产物偏大**：JS ≈ 1.05 MB（gzip 341 KB），因为 Element Plus 是**全量引入**。内部工具可接受；
  要优化就上 `unplugin-vue-components` 做按需引入。
- **范围**：待办列表 + 详情 + 裁决 + 统计卡。**不含**历史趋势图、规则可视化配置、合规监控
  （后两者是原文里已排除的非目标）。
