# syntax=docker/dockerfile:1

###############################################################################
# ecom-flowcart —— app 模块多阶段镜像
# 最佳实践：多阶段构建（build → 分层提取 → runtime）/ 非 root 用户 / HEALTHCHECK /
#           BuildKit 依赖缓存 / 层序 = 低变更 → 高变更（最大化缓存命中）
# 构建对象 = app 模块（聚合装配可执行 jar，见 app/pom.xml）
###############################################################################

# ---------- Stage 1: build（编译 + 打包） ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Aliyun 国内镜像 settings（依赖下载确定性；与根 pom repositories 同源）
# 注意：不能放在 /root/.m2 下 —— 下方 RUN 的 --mount=type=cache,target=/root/.m2 会
# shadow 该目录，使 settings.xml 在 RUN 内不可见（BuildKit 确定性失败）。放 WORKDIR 下。
COPY .mvn/settings-aliyun.xml /workspace/settings.xml

# 复制全部源码与 pom（仓库体积小；.dockerignore 已排除 target/.git/.workbuddy 等）
COPY . .

# BuildKit cache mount：~/.m2 跨构建缓存，依赖层不因源码变更而重复下载
# 仅构建 app 模块及其上游（-am），其余模块不打包（空壳，无发布产物）
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -s /workspace/settings.xml -pl app -am clean package -DskipTests

# ---------- Stage 2: 分层提取（tools jarmode，Boot 4 已移除 layertools） ----------
# --launcher 输出传统分层布局：extracted/{dependencies,spring-boot-loader,snapshot-dependencies,application}
FROM eclipse-temurin:21-jre AS extract
WORKDIR /workspace
COPY --from=build /workspace/app/target/app-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------- Stage 3: runtime（最小运行环境，非 root） ----------
FROM eclipse-temurin:21-jre

# 非 root 用户（安全最佳实践：容器进程不以 root 运行）
RUN groupadd --system flowcart && useradd --system --gid flowcart --home-dir /app flowcart

WORKDIR /app

# 按变更频率从低到高逐层 COPY（每层独立 Docker layer，应用代码变更只重建 application 层）
COPY --from=extract --chown=flowcart:flowcart /workspace/extracted/dependencies/ ./
COPY --from=extract --chown=flowcart:flowcart /workspace/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=flowcart:flowcart /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=flowcart:flowcart /workspace/extracted/application/ ./

USER flowcart

EXPOSE 8080

# 健康检查：TCP 探测 8080（bash /dev/tcp，免装 curl/wget；actuator /actuator/health 就绪后可换 HTTP 探活）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
