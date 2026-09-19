# Kubernetes 部署清单（原文 §17「部署文档 | Docker + Kubernetes」）

## 文件

| 文件 | 内容 |
|---|---|
| `config.yaml` | `ConfigMap`（非敏感配置）+ `Secret`（**模板**，含 `REPLACE_ME`） |
| `app.yaml` | `Deployment`（单副本、非 root、只读根文件系统）+ `Service` + `Ingress` |

## 应用顺序

```bash
# 1) 替换占位符（镜像地址、域名、TLS secret 名、所有 REPLACE_ME）
$EDITOR k8s/app.yaml k8s/config.yaml

# 2) 先建配置与密钥，再建工作负载
kubectl apply -f k8s/config.yaml
kubectl apply -f k8s/app.yaml

# 3) 观察
kubectl get pods -l app.kubernetes.io/name=tgg
kubectl logs -l app.kubernetes.io/name=tgg --tail=50
```

**判定「起来了」的依据是日志里那行 `Started TggApplication in N.NNN seconds`**，不是 Pod 变成 `Running`——
后者只说明容器进程还在，不代表 Flyway 迁移与组件装配已完成（本项目**未引入 Actuator**，
探针只能用 `tcpSocket`，它只证明端口在监听）。

## 前置条件

- [ ] 镜像已推送到你的 registry（`docker build -t <registry>/tgg-app:0.1.0-SNAPSHOT .` 后 push）
- [ ] MySQL 可达（清单里指向服务名 `mysql`；用托管数据库时改 `TGG_DB_URL`）
- [ ] 域名与 TLS 证书就绪（Ingress 终止 TLS）
- [ ] **`TGG_WEBHOOK_SECRET` 已填真实值**——缺失即启动失败（刻意 fail-fast）
- [ ] 已为 Telegram 配置 webhook 指向 `https://<域名>/webhook`，且 `secret_token` 与上者一致

## 与 `docker-compose.yml` 的关系

两者**不叠加使用**：Compose 面向单机（含 MySQL），本目录面向已有 K8s 集群的环境。
镜像与配置项是同一套（都来自 `Dockerfile` 与 `README.md` 的环境变量表）。

## ⚠️ 已知限制与注意

1. **`replicas` 必须为 1**。Flyway 迁移无锁协调；`@Scheduled` 维护任务（保留策略、各类催办）
   **无分布式锁**，多副本会重复执行与重复通知。
2. **`/admin/*` 默认未在 Ingress 开放**——它是运营面。要开放请配合来源 IP 白名单
   （`nginx.ingress.kubernetes.io/whitelist-source-range`），token 之外再加一层。
3. **Actuator 已引入并锁定**：只暴露 `/actuator/health`（`show-details=never`），可用于
   `httpGet` 就绪/存活探针；`k8s/app.yaml` 目前用的是 `tcpSocket`，想改成业务就绪探针就把它换成
   `httpGet: { path: /actuator/health, port: 8080 }`。
   ⚠️ **CSP/HSTS 仍不在应用层**（HSTS 必须由 TLS 终止方下发、CSP 约束的是不由 Spring 托管的静态站），
   请在入口层补。
4. **Secret 是模板**：`k8s/config.yaml` 里的 `stringData` 只是字段清单。生产请用
   External Secrets / Sealed Secrets / 云密钥服务注入，**不要把明文提交进版本库**。
5. **本清单未经 Kubernetes 集群验证**：开发机没有 `kubectl`，交付前只做了 YAML 结构解析。
   首次在真实集群应用时请逐项核对（尤其 `securityContext` 与存储类的差异）。
