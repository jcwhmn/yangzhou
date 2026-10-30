# spec 0087 V45 YPJ-118 Docker 容器化:应用镜像 + 三 profile compose

票:YPJ-118。三个 profile 全容器化(web+api 成对),postgres/rustfs 仍为外部共享实例,不动。

## 一、需求

### 背景
三 profile 原为 host 进程形态(bootRun / next start)。统一为容器:环境一致、起停一键、端口格局固化。

### 端口格局(拍板,2026-10-30)

| 环境 | web | api | 库 | compose 文件 |
|---|---|---|---|---|
| prod(PM 日常) | 3000 | 8080 | yangzhou | docker-compose.prod.yaml |
| dev(scratch) | 3001 | 8081 | yangzhou_dev | docker-compose.dev.yaml |
| test(E2E) | 无(E2E 前端由 Playwright webServer 拉起) | 8082 | yangzhou_test | docker-compose.test.yaml(仅 api) |

- 容器内端口统一:web 3000、api 8080;host 映射按上表
- 每文件独立默认 network,web 经服务名访问 api(同 network)
- api → postgres/rustfs:`host.docker.internal`(Docker Desktop;本票不覆盖 Linux CI)

### 范围
1. `backend/Dockerfile`:单阶段,`eclipse-temurin:25-jre` + COPY fat jar(宿主 `gradle :api:bootJar` 先行;不在容器内跑 gradle——国内下发行包慢,已知)
2. `web/Dockerfile`:node(22-alpine)两阶段——build 阶段 `npm ci && next build`,run 阶段只 COPY `.next/standalone` + `.next/static` + `public`(next.config 已有 `output: "standalone"`,零配置改动)。web 镜像容器内自包含构建,不需宿主先 build(npm 走 registry 镜像源,与宿主日常 dev 同通路)
3. 三个 compose 文件(env 注入 profile 差异):
   - api:`SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/<库>` + `YANGZHOU_ATTACHMENT_ENDPOINT=http://host.docker.internal:9000`;prod 用 default profile、dev 用 `SPRING_PROFILES_ACTIVE=dev`、test 用 `SPRING_PROFILES_ACTIVE=test`
   - web:`BACKEND_URL=http://api:8080`(rewrites 代理目标,同 network 服务名)
4. `script/dev-infra.ps1` 扩展或并列脚本:build / up / down / status,支持指定环境(prod|dev|test)
5. AGENTS.md「构建与运行」节更新:三 profile 跑法从 bootRun 命令改为 compose 命令为主、bootRun 降级为调试手段

### 非目标
- postgres 不进 compose(外部共享实例,既定决策;`reset-db.mjs` 走 `docker exec postgres` 不受影响)
- rustfs compose 不动
- 不动 CI(backend.yml/e2e.yml 仍 host 形态;容器化 CI 另票)
- 不做镜像推送 registry(本地构建本地用)
- Linux CI 的 `host.docker.internal` 兼容(host-gateway)不做

### 调试形态(并存,不废除)
- 改码调试循环仍走 IDE/gradle bootRun。注意端口:8080 归 prod 容器、8081 归 dev 容器——**bootRun 调试 test profile 须指 `--server.port=8082` 且 test 容器已停**,或先 down 对应容器
- E2E 跑法不变(playwright webServer 拉前端),但 `BACKEND_URL`/`E2E_API_URL` 改指 **8082**(原 8080 现为 prod 容器,不改会污染 PM 库——本 spec 范围内必须同步改 e2e 文档/注释;`.env` 用户本地文件,提示手改)

## 二、详细设计(代码级)

### 1. `backend/Dockerfile`(新建)
```dockerfile
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY api/build/libs/api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
```
- profile/数据源全靠 env(SPRING_PROFILES_ACTIVE / SPRING_DATASOURCE_URL),镜像单一、环境差异在 compose
- `api-*.jar` 通配避开 plain jar:bootJar 产物 `api-0.1.0-SNAPSHOT.jar` 与 `api-...-plain.jar` 同目录,COPY 通配可能双匹配——改为固定名:构建脚本 bootJar 后复制为 `api/build/app.jar`,Dockerfile COPY 该固定名(见脚本)

### 2. `web/Dockerfile`(新建)
```dockerfile
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json pnpm-lock.yaml ./   # 或 package-lock,以实际锁文件为准
RUN corepack enable && pnpm install --frozen-lockfile
COPY . .
RUN npx next build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
EXPOSE 3000
CMD ["node","server.js"]
```
- 实测前核对:包管理器(pnpm,从进程路径 `node_modules/.pnpm` 判断)、lock 文件名、public 目录存在性
- `BACKEND_URL` 是**构建期**读的(next.config.mjs 顶层)——standalone 下 rewrites 在 server 启动时求值,需验证 build 期固化还是运行期读取;若固化,则 run 阶段 env 无效,改为 build 时 ARG 传入(每个 compose build 传不同 `--build-arg BACKEND_URL`),或改 next.config 支持运行期(倾向:实测后择简,/spec 补 As-built)

### 3. compose 文件(三个,结构同构)
以 prod 为例(`docker-compose.prod.yaml`):
```yaml
services:
  api:
    build: ../../backend
    image: yangzhou-api:local
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://host.docker.internal:5432/yangzhou
      YANGZHOU_ATTACHMENT_ENDPOINT: http://host.docker.internal:9000
    ports: ["8080:8080"]
    extra_hosts: ["host.docker.internal:host-gateway"]   # Desktop 下冗余、未来 Linux 兼容,无害
  web:
    build: ../../web
    image: yangzhou-web:local
    environment:
      BACKEND_URL: http://api:8080
    ports: ["3000:3000"]
    depends_on: [api]
```
- dev:同构,api 端口 `8081:8080`、web `3001:3000`、库 `yangzhou_dev`、`SPRING_PROFILES_ACTIVE: dev`
- test:仅 api 服务,`8082:8080`、库 `yangzhou_test`、`SPRING_PROFILES_ACTIVE: test`
- restart 策略:prod/dev 用 `unless-stopped`(常驻),test 不加(E2E 场景短命)
- JWT secret:default 值沿用 yml(dev/test 本就不敏感);不注入生产 secret(本机部署形态,另票)

### 4. 脚本 `script/app-infra.ps1`(新建,仿 dev-infra.ps1)
```
.\script\app-infra.ps1 <build|up|down|status> <prod|dev|test>
```
- build:gradle `:api:bootJar` → 复制 jar 到 `api/build/app.jar` → `docker compose -f <对应文件> build`
- up/down/status:对应 compose 文件操作;down 不带 -v(无卷,天然无损)
- prod up 前检查 host 8080/3000 占用,占用则报错提示先停对应进程(防 PM bootRun 撞车)

### 5. E2E 对接改动
- `web/playwright.config.ts` 头注释、`web/e2e/.env.example`:8080 → 8082 示例更新
- `web/e2e/.env`(用户本地):提示用户手改 E2E_API_URL/BACKEND_URL → http://localhost:8082

### 否决的备选
- 容器内 gradle/npm 多阶段构建后端(下载发行包慢,已知网络问题)→ 宿主构建 + COPY
- 单 compose 文件 + profile 切换(compose profiles 语义与端口映射组合易错,且用户明确要三文件)
- postgres 进 compose(违背 ADR-0004 外部共享实例决策)

## 三、手工测试用例(用户 build/debug 阶段执行)

前置:停 host 上 3000/8080/8081 进程(现网都有);`gradle :api:bootJar` 成功。

1. **build 三连**:`.\script\app-infra.ps1 build prod|dev|test` 全绿,`docker images` 见 yangzhou-api/yangzhou-web:local
2. **test 起**:`up test` → curl `http://localhost:8082/actuator/health`(或登录接口)通;`curl http://localhost:3000` 拒绝(test 无 web)
3. **dev 起**:`up dev` → web `http://localhost:3001` 能登录(空库先 bootstrap)、API `8081` 通;web 页面发请求成功(代理链路通)
4. **prod 起**:`up prod` → web `http://localhost:3000`、API `8080` 通,PM 真实数据可见(yangzhou 库);yz 指向 8081 的 session 改指 8080 后操作正常
5. **隔离**:三环境同时只起 prod+test,各自数据不串
6. **E2E 全量**:`.env` 改 8082 后,web/ 下标准全量跑法绿(playwright 自己拉前端)
7. **down**:`down prod` 后 3000/8080 释放

### 验收清单
- [ ] 三个 compose 文件 + 两个 Dockerfile + 脚本入 repo
- [ ] AGENTS.md 运行节更新(compose 为主、bootRun 调试附端口约束)
- [ ] e2e 配置注释/.env.example 更新至 8082
- [ ] 手工用例 1–7 全过
- [ ] spec 补 As-built(含 BACKEND_URL 构建期/运行期实测结论)


## As-built(2026-10-30,PR #160)

### 实测结论(spec 预留的不确定点)

1. **BACKEND_URL = build 期固化,实锤**:`next build` 把 rewrites destination 烤进 `routes-manifest.json`(`_originalRewrites`),运行期 env 无效。因 compose 内 web→api 恒为 `http://api:8080`,三环境同值 → **单 web 镜像通吃**,Dockerfile `ARG/ENV BACKEND_URL` 写死默认值,连 build-arg 都不用传。
2. **web 镜像**:node:22-alpine 两阶段 standalone(无 public 目录,不 COPY);容器内 `npm ci`(host 用 pnpm 互不影响)。

### 与 spec 的偏差(全部更优)

- **compose project name 三文件互异**(`yz-prod`/`yz-dev`/`yz-test`):spec 未提,实测同名 project 容器互相顶替——每 up 一个环境上一个环境即被替换。本票最大隐性坑。
- **prod JWT secret**:`JwtService` 护栏拒绝默认密钥 → gitignored `infra/compose/prod.env`(prod compose `env_file` 注入;重生成=重新登录,零数据风险)。
- **CI e2e 拓扑显式化**:CI 无容器、bootRun 在 8080,workflow 显式 `E2E_API_URL`/`BACKEND_URL`→8080;本地默认 8082(test 容器)。

### 新增实证坑(对后人最值钱)

- **`localhost` 的 `::1` 回环间歇拒连**:Docker Desktop 端口发布经 wslrelay,IPv6 回环路径不稳定。**docker 发布的端口一律写 `127.0.0.1`**,已固化在 e2e 配置与 webServer 默认值。
- **容器发布的端口不可 killport**:持有者是 `com.docker.backend`/`wslrelay`,杀之=Docker 瘫痪(实锤一次)。收容器用 `app-infra.ps1 down`;killport 已加保护名单。
- **PS 5.1 读无 BOM 的 .ps1 按 GBK 解码**:中文注释搅坏解析,`app-infra.ps1` 必须带 UTF-8 BOM。
- **E2E 端口迁 3002**:3001 已归 dev web,复用必撞。

### 验证记录

- 两镜像构建绿;test 容器 Flyway+登录 200;E2E 全量 54 例绿(1 例 epic 409 竞态 flake 复跑过;retries 挂账仍开放)
- prod/dev/test 三容器并存互不干扰;dev 库重建(旧 bootstrap 密码不可考,scratch 语义重建)
- CI:build + e2e(@smoke)全绿
