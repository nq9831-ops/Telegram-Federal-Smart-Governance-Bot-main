# 管理员手册（ADMIN-GUIDE）

> 面向**运营者 / 平台管理员 / 复核人**：审批中心怎么用、复核怎么做、日常要盯什么。
> 群内命令见 `USER-GUIDE.md`；部署见 `README.md` 与 `docs/DEPLOYMENT-RUNBOOK.md`；合规义务见 `COMPLIANCE.md`。

## 一、本手册覆盖什么

| 部分 | 内容 | 状态 |
|---|---|---|
| **审批中心**（模块十一） | 复核队列的 REST 后台：列表 / 详情 / 统计 / 裁决 | 已实现（**仅后端，无界面**） |
| **群内复核** | `/review_list`·`/review_approve`·`/review_reject` | 已实现（见 `USER-GUIDE.md`） |
| 运营者日常 | 保留策略、泄露通报、依赖与漏洞、部署验证 | 已实现，需运营者主动执行 |

**已有可视化界面**：`frontend/`（Vue 3 + TypeScript + Element Plus）——待办列表 / 案件详情 /
裁决 / 统计卡片，独立构建为静态站点、经反向代理调本页的端点。构建与部署见 `frontend/README.md`。

下面的 **HTTP + curl** 用法仍然有效，而且是**排障时的第一手手段**：界面报错时，先用 curl 打同一个
端点，就能立刻分清「是接口的问题」还是「是界面/代理的问题」。

### Swagger / OpenAPI（可选，**默认关闭**）

```bash
export TGG_OPENAPI_ENABLED=true     # 默认 false；需重启应用
```

| 地址 | 内容 |
|---|---|
| `/swagger-ui.html` | 交互式界面（可在浏览器里直接试调端点） |
| `/v3/api-docs` | OpenAPI 3.1 JSON（可喂给 Postman 或代码生成器） |

⚠️ **它是一条信息面**：会把全部 `@RestController`（**包括 TelegramBots 的 `/{botPath}`**）的
端点清单与参数结构暴露出来。因此本项目**默认关闭**；启用后**不要**裸露到公网——
只在内网开放，或用反向代理加来源 IP 白名单（K8s 下见 `k8s/app.yaml` 里 `/admin` 那段注释）。

> Swagger 只描述**接口形状**，不描述**业务约束**（「不可自审」「幂等」「只告警不处置」等都在本文
> 下面的章节里）。两者互补，不要只依赖 Swagger。

## 二、启用

```bash
TGG_ADMIN_API_TOKEN=<强随机串>          # 必填。未配置（或为空/空白）时端点整体不装配
TGG_MODERATION_REVIEWERS=<userId,...>   # 审批人白名单（与 /review_* 同一套定义）
```

启动日志应出现「审批中心已启用」字样。**未出现**即表示 token 没生效——此时访问 `/admin/approvals`
会得到 **404**（端点根本不存在），这是**刻意的 fail-closed**，不是故障。

其余可调项（`tgg.admin.*`，均有默认值）：

| 键 | 默认 | 含义 |
|---|---|---|
| `tgg.admin.overdue-remind-hours` | `24` | 待办超过该小时数 → 提醒 |
| `tgg.admin.overdue-escalate-hours` | `72` | 待办超过该小时数 → 升级提醒 |

## 三、鉴权（两层，缺一不可）

```
Authorization: Bearer <TGG_ADMIN_API_TOKEN>     ← 证明「够得着后台」
X-Operator-Id: <userId>                          ← 证明「有权审批」，必须落在 TGG_MODERATION_REVIEWERS 内
```

- token 用**常量时间比对**（`MessageDigest.isEqual`），不按字节短路。
- **两层分工是刻意的**：token 是共享密钥、不指向具体的人；「谁能审批」由白名单定义——
  与群内 `/review_*` 同源，不引入第二套权限体系。
- 两个缺口的响应不同：**缺 token → 401**；**operator 不在白名单 → 403**。

## 四、端点参考

Base：`http://<host>:8080/admin/approvals`

```bash
export TGG_ADMIN_TOKEN='<tgg.admin.api-token>'
export OPERATOR='<复核人 userId>'
export BASE='http://127.0.0.1:8080'
AUTH=(-H "Authorization: Bearer $TGG_ADMIN_TOKEN" -H "X-Operator-Id: $OPERATOR")
```

### 1. 待办列表

```bash
curl -s "${AUTH[@]}" "$BASE/admin/approvals?status=PENDING&page=0&size=20" | jq
```

返回 `Page{items, total, page, size}`。排序键是**硬红线 → 风险等级降序 → 入队时间升序（先入先审）**，
每条含 `ageHours` 与 `overdue`。`status` 可选 `PENDING`（默认）/ `APPROVED` / `REJECTED`。

### 2. 统计

```bash
curl -s "${AUTH[@]}" "$BASE/admin/approvals/stats" | jq
```

返回 `Stats{pending, approved, rejected, hardLinePending, overdueRemind, overdueEscalate, avgDecisionHours}`。

### 3. 详情

```bash
curl -s "${AUTH[@]}" "$BASE/admin/approvals/123" | jq
```

**不存在 → 404**（不是 200 空体）。本项目有一个全局异常处理器把异常吞成 200（为 Telegram 避免重试风暴），
故这些端点的状态码一律由 `ResponseEntity` 显式设置，不靠抛异常。

### 4. 裁决

```bash
curl -s -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVED","reason":"确认违规"}' \
  "$BASE/admin/approvals/123/decide" | jq
```

- `decision` 取 `APPROVED`（维持结论 / 确认违规）或 `REJECTED`（推翻 / 判定误报）。
- `reason` 是裁决理由，**不要粘贴消息正文**（队列本就不含正文，正文也不应经此处留存）。
- **驳回（REJECTED）会执行反向动作**：推翻一条硬红线会**解封**当事人——这是防「误封真人」的安全网。

## 五、裁决流程与约束

| 约束 | 行为 |
|---|---|
| **不可自审** | `X-Operator-Id` 等于案件当事人 → **403**（`self-decision-forbidden`），状态不变 |
| **幂等** | 对同一条重复裁决 → 结论**不被翻转**，返回 `ALREADY_DECIDED`；审计记 FAILURE（本次未改变任何东西） |
| **审计** | 每次 Web 裁决写 `audit_log`：`action=admin.approval.decide`，含 operator 与结果 |
| **审计不可删** | `audit_log` 在应用层**无删除入口**（保留策略硬编码排除该表） |
| ⚠️ **不可自审只施加于 Web 侧** | 群内 `/review_approve`·`/review_reject` **没有**这条校验（原文约束写在「Web后台」章节下） |

裁决落地后，相应的 Telegram 动作（禁言 / 解封）由既有的 `ModerationReviewDecisionService` 执行，
依赖 `TGG_BOT_TOKEN`。

## 六、待办超时提醒

`ApprovalOverdueJob` 是定时任务：

- 超过 `overdue-remind-hours`（默认 24h）→ 向复核人白名单发**提醒**；
- 超过 `overdue-escalate-hours`（默认 72h）→ 发**升级**提醒；
- ⚠️ **硬红线条目被刻意跳过**——它们由模块九 §10.6 的 **2 小时 SLA 催办**通道负责，
  重复催办只是噪音。

⚠️ 该任务**没有分布式锁**：多副本部署时每个副本都会扫描并通知（本项目未引 Redis）。

## 七、运营者日常清单

按重要性排序，每项都可执行：

1. **盯待办积压**：`GET /admin/approvals/stats` 的 `hardLinePending` 与 `overdueEscalate`。
   硬红线的 SLA 是 **2 小时**，不是 24 小时。
2. **保留策略**：默认**只报告不删除**。要真正清理需显式开启 `tgg.retention.enabled=true`，
   并确认期限符合你的法域要求（审计表**永不清理**，是刻意的）。
3. **数据泄露**：发生事件用 `/data_breach <影响范围> <影响人数>` 登记并开始 72 小时计时，
   履行通报后用 `/data_breach report <编号>` 留痕。**软件不会替你向监管或当事人发送任何东西**。
4. **依赖安全**：每次升级依赖后跑
   `mvn -B -ntp -DskipTests package && python3 tools/security/scan_dependencies.py`（退出码应为 0）。
   CI 已自动化这一步。⚠️ 扫描只覆盖公开漏洞，**基础镜像的 OS 包未扫**。
5. **密钥卫生**：`TGG_HASH_SALT` 必须设置（否则用开发兜底盐，userId 空间小可被枚举反推）；
   `TGG_WEBHOOK_SECRET` / `TGG_ADMIN_API_TOKEN` 用强随机；**定期轮换**（本项目未提供自动轮换）。
6. **部署验证**：`docs/DEPLOYMENT-VERIFICATION.md` 是运营者视角的验证清单（本地开发资产，不入 git）。

## 八、已知限制（刻意未做，非遗漏）

- **无界面**：只有 REST API（见本节开头）。
- **单一审批来源**：只聚合复核队列。原文 §12.1 的另三项（大额扣分 / 豁免申请 / 争议仲裁）在本项目
  **没有数据源**——大额扣分是自动动作、群标签豁免是「声明即生效」、争议仲裁属模块十二。
- **「大额扣分需双人审批」未实现**：会改变模块七现有的即时扣分行为，属产品决策，**未拍板**。
- **Actuator 已引入（锁定）**：只暴露 `/actuator/health`，可供容器/K8s 探**业务就绪**；
  其余端点（env / beans / configprops …）一律关闭。**CSP / HSTS 仍归反向代理层**（HSTS 须由 TLS 终止方下发）。
- **文本状态码**：`/admin/approvals` 之外的未知路径返回 200（全局异常处理器的既有行为）。
