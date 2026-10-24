# 0072 — YPJ-77 本机基础设施 compose 化(项目独用服务)

> 票:YPJ-77 · 影响:`infra/compose/`(新)、`script/dev-infra.ps1`(新)、AGENTS.md、`docs/architecture/`(无)

## 0. 约定变更(显要标注)

**旧约定废除**(原 AGENTS.md:「本机 Postgres 由共享 compose 提供(F:/code/docker-compose/),不建项目本地 compose」):按 2026-10-24 用户拍板改为——

> **postgres = 外部共享实例**(提供方 F:/code/docker-compose/postgresql,端口 5432 不变,库名 yangzhou / yangzhou_test),**不在本项目 compose 定义**;**项目独用服务**(rustfs,及后续 redis/prometheus/grafana)定义进 `infra/compose/docker-compose.yaml`。

原因:postgres 被多项目共用,不能起第二个实例、不改端口;rustfs 为 yangzhou 独用(F: 定义注释即「yangzhou 附件存储」)。

## 1. 需求

- 服务定义集中可见:项目独用基础设施的 compose 定义进仓;外部依赖(postgres)以注释形式声明(地址/库名/提供方/不定义的原因)
- 启动配置过程可见:`script/dev-infra.ps1` 一键 up/down/status,输出明确「postgres 不归本脚本管」

### 范围

1. `infra/compose/docker-compose.yaml`:rustfs(rustfs/rustfs:1.0.3,9000 S3 API / 9001 控制台,dev 凭据 rustfsadmin);卷名沿用 `rustfs_data`/`rustfs_logs`(与 F: 定义一致,迁切换数据无缝)
2. `script/dev-infra.ps1`:`up`/`down`/`status` 三个动作;up 时打印服务清单 + postgres 外部依赖提示
3. AGENTS.md「构建与运行」节改写为新约定(上引原文)

### 非目标

- postgres 定义迁移/复制(共享实例,刻意不做);备份自动化(另票);env/凭据管理(dev 默认明文,生产另议);CI 变更;redis/prometheus/grafana 实装(用到再加,注释占位)

## 2. 详细设计

- `infra/compose/docker-compose.yaml`:文件头注释块 = 外部依赖说明(「外部共享 postgres 不在本文件:多项目共用,见 F:/code/docker-compose/postgresql;本文件只定义 yangzhou 独用服务」);rustfs 定义从 F: 原样平移(container_name: rustfs 保留——后端按 localhost:9000 连接,不依赖 compose 网络)
- 迁移切换步骤(用户执行,手测 #3):F: 侧 `docker compose down`(**不带 -v**,保卷)→ 仓内 `docker compose -f infra/compose/docker-compose.yaml up -d` → 验证附件
- `script/dev-infra.ps1`:param($Action) switch up/down/status;内部 `docker compose -f $PSScriptRoot/../infra/compose/docker-compose.yaml`;中文输出(Write-Host)
- AGENTS.md:改写「本机开发库」条目 + 「docker compose:postgresql、rustfs(F:/code/docker-compose/)」处 → 新两分法约定 + 指向 infra/compose

否决:compose 里声明 `external` 网络挂 postgres(后端走 localhost,网络耦合无收益);把 postgres 定义抄进仓加 `profiles` 隔离(定义重复,双真相源)。

## 3. 手工测试用例

| # | 步骤 | 期望 |
|---|---|---|
| TC1 | F: `docker compose down`(不带 -v)→ 仓内 `dev-infra.ps1 up` | rustfs 容器起,9000/9001 可达 |
| TC2 | 传一张附件(yangzhou 附件功能)→ `dev-infra.ps1 down` → `up` | 附件仍可下载(卷数据未丢) |
| TC3 | 8080/8081 bootRun | 不受影响,正常连 postgres(localhost:5432)与 rustfs |

## As-built(2026-10-24,PR #143)

- 实现=设计原样:`infra/compose/docker-compose.yaml`(rustfs 平移,头注释声明外部 postgres)、`script/dev-infra.ps1`(up/down/status)、AGENTS.md 两分法新约定
- 过程修复:ps1 补 UTF-8 BOM(PowerShell 5.1 无 BOM 按 GBK 解码,中文串解析错)——后续含中文的 ps1 一律带 BOM
- 用户实测:F: 侧 down → 仓内 up 切换成功,附件数据无缝(卷沿用);TC1~TC3 全过
- 偏离记录:无
