# 0014 — V17-1 测试拆分:unit/integration 双轨,CI 只跑 unit + nightly 兜底

> 状态:**设计评审中** —— 待用户确认后编码;编码完成后停,等用户 build/debug
> 票:YPJ-14 · 前置:无 · 影响面:backend 构建脚本 / CI workflows / AGENTS 约定

## 1. 需求(详细)

### 1.1 背景与问题

现状 43 个测试文件(backend):
- **domain 纯单元**:4 个(Judge/MatchItem/RankCandidates/RollupProject)——引擎纯函数,无 Spring/DB
- **api 集成**:39 个文件,其中 38 个测试类全部 `extends AbstractApiTest`(`@SpringBootTest(RANDOM_PORT)` + Testcontainers 真 Postgres,容器 JVM 级单例跨类复用,每测试自清全表)
- **api 纯单元**:1 个(RealGithubGatewayPrParseTest,纯解析)

CI(`backend.yml`)在 push/PR 跑 `gradle build` = **全量**:
- 集成测试起完整 Spring Boot 上下文 + Testcontainers → CI 必须有 Docker、耗时长
- 用户决策(2026-10-09):**CI 只跑 unit;集成测试 merge 前本人本地执行;nightly 全量兜底**防"未测先合"

### 1.2 术语与判定

- **unit**:不起 Spring 上下文、不碰 DB/网络、无共享可变全局态
- **integration**:`extends AbstractApiTest`(唯一 seam = REST API 黑盒,Testcontainers 真 Postgres)

### 1.3 范围

改:Gradle 测试双轨、CI workflow、AGENTS 测试约定。
不改:任何测试文件内容、web E2E(YPJ-15 另票)、bootRun/profile 体系、persistence/cli(无测试)。

### 1.4 验收

= YPJ-14 票内验收清单;手工验证见 §3。

## 2. 详细设计(代码级修改计划)

### 2.1 机制选型:基类 @Tag + Gradle 双 Test 任务(**不搬文件**)

- `AbstractApiTest` 加 `@Tag("integration")`。JUnit 5 的 `@Tag` 标注 `@Inherited`,**子类自动继承** → 38 个集成测试类零改动归轨;新集成测试只要继承基类即自动带 tag
- 同一个 `test` sourceSet 挂两个 Test 任务:`tasks.test` 排除该 tag,新增 `integrationTest` 任务包含该 tag
- **否决的备选**:39 文件迁 `src/integrationTest/` —— diff 噪音大、git blame 断裂,收益为零

### 2.2 修改文件清单

1. `backend/api/src/test/kotlin/yangzhou/api/AbstractApiTest.kt`
   - 类上加 `@Tag("integration")`(1 行 + 1 import)
2. `backend/api/build.gradle.kts`
   - `tasks.test` 现有块内加 `useJUnitPlatform { excludeTags("integration") }`(现有 testLogging 原样保留)
   - 新增 `integrationTest` 任务:`group="verification"`,`includeTags("integration")`,`testClassesDirs`/`classpath` 复用 test sourceSet,`shouldRunAfter(tasks.test)`,testLogging 与现有一致
   - `tasks.check { dependsOn(integrationTest) }` → 本地 `gradle build` 仍全量,行为不变
3. `.github/workflows/backend.yml`
   - `- run: gradle build` → `- run: gradle test`(PR/push 快轨;不再需要 Docker)
4. `.github/workflows/nightly.yml`(新建)
   - 触发:`schedule` cron `0 20 * * *`(UTC 20:00 = 北京 04:00)+ `workflow_dispatch`(手动触发便于验证 TC5)
   - 步骤同 backend.yml(setup-java/gradle),跑 `gradle build` 全量(集成含 Testcontainers,ubuntu runner 自带 Docker)
   - 失败 = Actions 红 X + GitHub 默认邮件通知
5. `AGENTS.md`
   - 测试段:「CI 跑」改为「CI 只跑单元;集成 merge 前本地跑 `gradle integrationTest`;nightly 兜底」;新增「集成测试必须 extends AbstractApiTest(靠 @Tag 继承归轨)」
   - 工作流段:落本 session 新协议三则(需求文档写细;每票实现前产「详细设计 + 手工测试用例」;编码完成后停等用户 build/debug)

### 2.3 不动清单

domain 4 单元测试 · RealGithubGatewayPrParseTest(进快轨)· 38 个集成测试文件本体 · persistence/cli · `application-test.yml` · `tasks.withType<Test> { useJUnitPlatform() }` 总配置。

### 2.4 风险与对策

| 风险 | 对策 |
|---|---|
| 新集成测试不继承基类 → 漏 tag 混进快轨 | AGENTS 立规:集成测试必须 extends AbstractApiTest;TC1 显式验证无 *ApiTest 混跑 |
| CI 不再跑集成 → main 带病窗口 | nightly 兜底 + merge 前本地纪律(用户已知拍板);TC5/TC6 验证兜底可见性 |
| `@Tag` 继承是设计支柱,若 JUnit 行为不符预期则全盘失效 | TC1 直接检验;不符则回退为显式逐类标注(升级路径明确) |

## 3. 手工测试用例(用户执行)

前置:Docker Desktop 运行中;`backend/` 目录;`gradle` 或 `./gradlew` 均可。

| # | 步骤 | 期望 |
|---|---|---|
| TC1 | `gradle test` | 秒级完成;**不**拉起 Postgres 容器;输出只有 domain 4 文件 + RealGithubGatewayPrParseTest,无任何 `*ApiTest` |
| TC2 | `gradle integrationTest` | 38 个集成测试全绿;可见 Testcontainers 启动 |
| TC3 | `gradle build` | test 与 integrationTest 都执行(本地全量不变) |
| TC4 | 推分支开 PR | backend.yml 绿,时长明显缩短(无容器步骤) |
| TC5 | GitHub Actions 手动 dispatch `nightly` | 全量绿(含集成) |
| TC6 | (可选)某集成测试临时注入失败 → 手动跑 nightly | nightly 红 X + 收到邮件;验证兜底可见性后改回 |

## As-built

(2026-10-10 补录,YPJ-16 PR 搭车)

- **落地 = 计划,零偏差**(2026-10-10 逐项核对代码):`AbstractApiTest` 类级 `@Tag("integration")`(L22);`tasks.test` `excludeTags("integration")`,新增 `integrationTest` 任务 `includeTags`(同 sourceSet,`check` 依赖之);`backend.yml` 跑 `gradle test`(免 Docker);`nightly.yml` cron `0 20 * * *` + dispatch 全量。
- 38 个集成测试类零改动归轨(现库 grep 到 39 个文件 = 基类 + 38 子类,后续票新增者自动继承)。
- `@Tag` 继承实证有效(风险③未发生);AGENTS 测试段/工作流段按计划改写,「双轨」纪律(2026-10-09)已运行多票。
- 验证:CI backend.yml 快轨绿(TC4);nightly 手动 dispatch 全量绿(TC5)。TC6(注入失败验兜底)按计划属可选项,未执行——nightly 红信通道未实证,首红时留意邮件可达性。

