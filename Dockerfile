# syntax=docker/dockerfile:1

# ═════════════════════════════════════════════════════════════════════════════
# 阶段 1 · 构建（Maven + JDK 21）
#   - 依赖层用 BuildKit cache mount 复用 ~/.m2：改一行 Java 不必重下全部依赖。
#   - 跳过测试：集成测试需要 MySQL（见根 pom 的 failsafe 配置），构建镜像时没有；
#     测试由 `mvn verify` 与 CI 负责，不在这里跑。
#   - `package` 同时产出聚合 SBOM（target/bom.json，见根 pom.xml 的 cyclonedx 插件）。
# ═════════════════════════════════════════════════════════════════════════════
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 先只复制 POM：它们不常变，这一层可被缓存，避免改代码就重新解析依赖
COPY pom.xml ./
COPY tgg-common/pom.xml tgg-common/
COPY tgg-core/pom.xml tgg-core/
COPY tgg-credit/pom.xml tgg-credit/
COPY tgg-federation/pom.xml tgg-federation/
COPY tgg-listing/pom.xml tgg-listing/
COPY tgg-admin/pom.xml tgg-admin/
COPY tgg-app/pom.xml tgg-app/

COPY tgg-common/src tgg-common/src
COPY tgg-core/src tgg-core/src
COPY tgg-credit/src tgg-credit/src
COPY tgg-federation/src tgg-federation/src
COPY tgg-listing/src tgg-listing/src
COPY tgg-admin/src tgg-admin/src
COPY tgg-app/src tgg-app/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.test.skip=true package

# ═════════════════════════════════════════════════════════════════════════════
# 阶段 2 · 运行（仅 JRE 21，非 root）
# ═════════════════════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre AS runtime

# 非 root 运行：uid/gid 固定为 10001，便于在 K8s securityContext / 卷属主上对齐
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
# spring-boot-maven-plugin 的 fat jar（`.original` 后缀的薄 jar 不匹配 *.jar，不会误拷）
COPY --from=build --chown=app:app /workspace/tgg-app/target/tgg-app-*.jar /app/app.jar

USER app
EXPOSE 8080

# 容器内存感知：按 cgroup 限额取 75%，而不是按宿主机物理内存
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# 存活探测：直接探监听端口。本项目**未引入 Actuator**，故没有 /actuator/health 可探。
# ⚠️ 若改了监听端口（SERVER_PORT），这里要同步改。
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

# shell 形式以便 JAVA_OPTS 可被外部覆盖；exec 保证信号直达 JVM（SIGTERM 能优雅停机）。
# 注意：应用对 TGG_WEBHOOK_SECRET 等关键变量 fail-fast（缺失即启动失败）——这是**刻意**的，
#       不要在此设默认值，按 docs/DEPLOYMENT-RUNBOOK.md 注入。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
