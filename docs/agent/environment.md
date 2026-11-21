# environment — 运行手册

> 由 AGENTS.md §真相源 触发加载:起停容器/查端口/动数据库/杀进程/CLI 中文乱码时必读。

## 三 profile 与端口格局(2026-10-30 重排,YPJ-118 容器化)

**3000(web)+8080 = prod / PM 日常**(default profile,库 `yangzhou`,dogfood 项目 YPJ,真实数据);**3001(web)+8081 = dev**(dev profile,库 `yangzhou_dev`,**仅手工 UI 测试**:含准真实数据,禁止自动清库,自动化测试不得指 dev;空库首启用 `POST /api/auth/bootstrap` 引导);**8082 = test**(test profile,库 `yangzhou_test`,**唯一自动化主场——e2e 与 api.http 都只在这里跑**,globalSetup 会清库属预期;无前端,E2E 前端由 Playwright 拉起;2026-11-08 用户拍板)。常驻起停 `script/app-infra.ps1 <build|rebuild|up|down|status> <prod|dev|test>`;CLI `yz` 的 session 指向谁就写谁的库——动 PM 数据前确认 server 指向 prod(api 8080)。

## 本机开发库

`yangzhou`/`yangzhou_dev`/`yangzhou_test`(外部共享 postgres,已建)。镜像 2 个(api/web),build 先宿主 `bootJar` 再 docker build。rustfs 起停走 `script/dev-infra.ps1`。调试改码走 IDE/gradle bootRun,但 test profile 须 `--server.port=8082` 且 test 容器已停(8080/8081 归容器)。

## 基础设施两分法(2026-10-24 拍板,废除旧「不建项目本地 compose」约定)

**postgres = 外部共享实例**(提供方 `F:/code/docker-compose/postgresql`,localhost:5432 不变,库 `yangzhou`/`yangzhou_dev`/`yangzhou_test`)——多项目共用,**不在本项目 compose 定义**;**项目独用服务**(rustfs,及后续 redis/prometheus/grafana)定义进 `infra/compose/docker-compose.yaml`,起停用 `script/dev-infra.ps1`(up/down/status,postgres 不归它管)。

## 构建与运行

- JDK 25(LTS)。后端在 `backend/`:`gradle build`(单元测试,不含集成)/ `gradle integrationTest`(集成,需 Docker)/ `gradle :domain:run`(引擎 Demo,输出与 prototype S1–S7 可比对)。`./gradlew` 同效;首次需下载发行包,国内网络慢属已知,用本地 gradle 即可。
- CI(GitHub Actions):`backend.yml` 跑 `gradle test`(纯单元,免 Docker;spec 0014);`nightly.yml` 每日全量兜底(含集成,红了必修);`e2e.yml` web/backend 变更起 bootRun + Playwright 整跑 E2E。
- PM 实例部署形态(已拍板):本机常驻即可;Postgres 备份手工期(`pg_dump`),自动化另票。

## 进程管理(本机实操)

- **严禁 `taskkill /IM node.exe`**——pi 本体就是 node 进程,按进程名杀会自杀;也慎杀全部 java.exe(gradle daemon 可杀,但用完再起更省)。停开发服务一律**按端口找 PID**:`netstat -ano | findstr :3000` → `taskkill /PID <pid> /F`。
- CLI/脚本中文输出在 PowerShell 显示乱码(UTF-8 被按 GBK 解码):先 `chcp 65001` 或改用 Git Bash。
- context-mode 批处理工具实际跑 PowerShell——`&&`、`find`、`/dev/null` 语法会挂;简单命令直接用 Bash;**大输出/多命令优先 context-mode 批处理工具,Bash 仅琐碎命令**。
- headroom 压缩开启时:被压缩的读取输出不可当编辑锚点(原文会被搅坏);大文件读改一律 python 原地处理。
- python 大文件手术:每次切片前 `repr()` 打印边界行断言;prettier 重排会搅行内注释/折行,锚点失配先查现文再改(未写入无损害,零成本重试)。
