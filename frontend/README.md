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

- **渲染未经真实浏览器验证**：交付时只跑了 `pnpm build`（类型检查 + 打包）与 `pnpm preview` 的
  HTTP 探测（200、`<title>` 正确、入口脚本可达、`<div id="app">` 存在）。
  界面在浏览器里的**实际呈现与交互未验证**。
- **产物偏大**：JS ≈ 1.05 MB（gzip 341 KB），因为 Element Plus 是**全量引入**。内部工具可接受；
  要优化就上 `unplugin-vue-components` 做按需引入。
- **范围**：待办列表 + 详情 + 裁决 + 统计卡。**不含**历史趋势图、规则可视化配置、合规监控
  （后两者是原文里已排除的非目标）。
