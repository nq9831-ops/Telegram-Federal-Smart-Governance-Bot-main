# 贡献指南（CONTRIBUTING）

## 一、环境

| 项 | 要求 |
|---|---|
| JDK | **21**（构建须显式指定——`JAVA_HOME=/opt/homebrew/opt/openjdk@21`） |
| Maven | 3.9+ |
| MySQL | 8.x（运行时库 + **独立的测试库**，见下） |

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -B -ntp verify
```

## 二、测试

**测试必须连独立库**：`mvn verify` 的集成测试会用 `deleteAll()` 重置数据（端到端用例要调
`@Scheduled` 作业与真实分发链，事务回滚对它们无效），与运行库同库会**清空生产数据**。
测试 URL 带 `createDatabaseIfNotExist=true`；若账号无建库权限，由管理员执行：

```sql
CREATE DATABASE tgg_test;
GRANT ALL ON tgg_test.* TO 'tgg'@'localhost';
```

**`*IT` 由 failsafe 在 `verify` 阶段执行**——只跑 `mvn test` 会**静默跳过**全部集成测试，
而 `BUILD SUCCESS` 并不代表端到端跑过。提交前请跑 `mvn verify` 并**逐模块核对用例数**。

## 三、代码约定

- 包名 `com.tg.heyisheng.bot`；模块划分与依赖方向见 `README.md`。
- **不引新依赖**，除非确有必要——尤其不要为一个功能引入整套框架。项目已有自研 RBAC、
  自研规则引擎、自研审计切面，取舍理由见 `docs/LESSONS.md`。
- 注释写**中文**，解释**为什么**这么做，而不是复述代码在做什么。
- 装配纪律：新增 `@Bean` 时注意它可能波及其他用 `ApplicationContextRunner` 装配同一
  `@Configuration` 的既有测试（需补替身）；给组件加开关条件时，**每个组件**都要挂，
  因为 `@RestController` / `@Service` 是组件扫描独立注册的，不受别的类上的条件约束。

## 四、提交前自检

- [ ] `mvn verify` 全绿，且**用例数已逐模块核对**（防静默跳过）
- [ ] 断言触碰**真实行为**，而不是状态码或「没抛异常」
- [ ] 跨模块接口变更后，**两侧调用方**已同步（`grep` 确认无遗漏）
- [ ] 无临时调试残留（探针、`System.out`、调试日志）
- [ ] 涉及破坏性操作（删数据、迁移、覆盖文件）的改动，已在描述中写明影响面

## 五、提交信息

格式：`feat|fix|refactor|docs|test|chore|perf: 简述`

**不 amend 已推送的提交，不 force push `main`。**

## 六、文档口径（本项目的底线）

面向用户的说明（隐私、合规、免责）必须**如实**：已实现就写已实现，**未实现就明确写未实现**。
不可用「规划中」「即将支持」暗示已具备的能力——`COMPLIANCE.md` 与 `PRIVACY.md` 的分节口径
就是本项目的底线，新文档请沿用同样的写法。
