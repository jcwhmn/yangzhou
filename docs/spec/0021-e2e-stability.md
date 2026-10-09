# 0021 — V17-8 E2E 稳定性:跑前自动清库 + 路由预热进 globalSetup,修标准命令

> 状态:**设计评审中** —— 待用户确认后编码;编码完成后停,等用户 build/debug
> 票:YPJ-21 · 前置:YPJ-14 合并(同动 AbstractApiTest/AGENTS)· 影响面:web/e2e、playwright 配置、AbstractApiTest、AGENTS

## 1. 需求(详细)

### 1.1 背景:两类已取证的 flake

**① 测试库残渣**(2026-10-09 实测):本地 `yangzhou_test` 持久库跨跑累积(304 项目、today 桶 72 条)→ `/api/dashboard` 聚合 5758ms → dashboard.spec 5s 断言超时。已手动 TRUNCATE 解决,但无自动化,会复发。

**② Fast Refresh 打断导航**:全量并行跑时 epic.spec / overview.spec **确定性**失败(单跑必过)。trace 取证(web/test-results/*/error-context.md + trace.zip):4 worker 共享一个 `next dev`;某 worker 首次踩到冷路由触发按需编译 → webpack 向所有已连页面广播 hot-update → `[Fast Refresh] rebuilding`(实测 2682ms)→ 进行中的客户端导航被掐死(RSC/chunk 请求 aborted),URL 停留原页 → 收尾的 `toHaveURL` 5s 超时。

**潜伏放大器**:`warmup.mjs`(路由预热)只在 `npm run e2e` 链路里生效;AGENTS/handoff 记的标准命令是裸 `npx playwright test`,绕过预热 → .next 冷缓存时必炸。另发现 warmup.mjs 硬编码 `npm run dev`(3000),与 `E2E_FRONT_PORT=3001` 场景不兼容。

### 1.2 目标

裸 `npx playwright test` **自给自足**:跑前自动 ①清测试库 ②预热路由 → 两类 flake 归零,残渣不再累积。

### 1.3 非目标

- 后端不加"清库端点"(危险接口,footgun)
- 不动 CI workflow:CI 的 postgres service 容器每次全新(无残渣),e2e.yml 自带 warmup 步骤;globalSetup 在 CI 下短路
- 不调大断言 timeout 掩盖性能信号

### 1.4 验收

= YPJ-21 票内验收清单;手工验证见 §3。

## 2. 详细设计(代码级修改计划)

### 2.1 时序要点

Playwright 顺序:**globalSetup → webServer 启动 → tests**。globalSetup 阶段 dev 尚未起——预热需自起临时 dev(同 CI warmup 模式:起→编译全路由→杀),.next 缓存落盘后 webServer 再起即热,零 on-demand 编译。

### 2.2 修改文件清单

1. `backend/script/reset-test-db.sql`(新)
   - 25 表 `TRUNCATE … RESTART IDENTITY CASCADE`(表清单以 2026-10-09 `pg_tables` 实查为准;**排除 `flyway_schema_history`**);文件头注释用途与来源
2. `web/e2e/reset-db.mjs`(新)
   - `process.env.CI` → 直接 return(CI 无 docker exec 场景,service 库本来就新)
   - `execSync`: `docker exec -i ${POSTGRES_CONTAINER||postgres} psql -U postgres -d yangzhou_test -v ON_ERROR_STOP=1 < backend/script/reset-test-db.sql`
3. `web/e2e/warmup.mjs`(重构)
   - 抽 `prewarm()` 导出(CLI 行为不变:`import.meta` 主入口判断);端口从硬编码 3000 改为读 `E2E_FRONT_PORT`(修 3001 场景)
   - 路由清单(13 条)保持单一真相源,CI 与 globalSetup 共用
4. `web/e2e/global-setup.mjs`(新)
   - CI → return
   - 步骤:reset-db → 探测 `${base}/login`:可达(用户已手起 dev)→ 对现成服务 fetch 路由预热;不可达 → spawn 临时 dev(`next dev -p ${E2E_FRONT_PORT||3000}`)→ 等就绪 → bootstrap/登录(WARMUP1)→ fetch 13 路由 → **杀掉临时 dev**
5. `web/playwright.config.ts`
   - + `globalSetup: "./e2e/global-setup.mjs"`
6. `web/package.json`
   - + `"db:reset": "node e2e/reset-db.mjs"`(手动清库命令)
7. `backend/api/src/test/kotlin/yangzhou/api/AbstractApiTest.kt`
   - `cleanDatabase` 表清单补 7 张缺表:`item_dependency, item_activity, checklist_item, comment, favorite, notification, status_transition`(现清单比 schema 少 7 张,靠级联删除兜底——补齐,插入位置按现有依赖序风格)
8. `AGENTS.md`
   - E2E 段:标准命令改为裸 `npx playwright test`(globalSetup 自带清库+预热);保留 env 变量要求;**BACKEND_URL 必须 8080**(rewrites 代理目标,8081 会污染 PM 库)

### 2.3 不动清单

CI 两个 workflow(backend.yml / e2e.yml)· 后端任何生产代码 · E2E 用例本体 · standup/dashboard 页面。

### 2.4 风险与对策

| 风险 | 对策 |
|---|---|
| globalSetup 临时 dev 与 reuseExistingServer 交互 | 探测逻辑:base 可达 → 直接对现成服务预热(不杀);不可达 → 临时 dev 用后即杀,webServer 随后自起 |
| docker 容器名变更 | `POSTGRES_CONTAINER` env 可覆盖,默认 `postgres`(共享 compose 实名) |
| AbstractApiTest 表清单扩充影响现有 38 个集成测试 | 只有自清更彻底,无行为变化;TC4 全量回归验证 |
| 每次跑多付预热成本(冷启动 ~1min) | 换 flake 归零;热 .next 时预热只是快速 fetch,开销小 |

## 3. 手工测试用例(用户执行)

| # | 步骤 | 期望 |
|---|---|---|
| TC1 冷缓存全量 | `rm -rf web/.next` → 标准命令全量(env 照旧 + `npx playwright test`) | 37/37 全绿;日志可见清库+预热输出;总时长 ≈ 原时长 + 1~2min |
| TC2 连跑复验 | 连续 3 次全量 | 每次全绿;每次起跑库是空的(残渣不累积);PM 实例 8081/日常 3000 不受影响 |
| TC3 手动清库 | `cd web && npm run db:reset` → `docker exec postgres psql -U postgres -d yangzhou_test -t -c "select count(*) from project"` | 输出 0 |
| TC4 集成回归 | `gradle integrationTest` | 38 个集成测试全绿 |
| TC5 CI 不回归 | 推分支看 e2e workflow | 绿(globalSetup 在 CI 短路,warmup 步骤保留) |

## As-built

(完工后补)
