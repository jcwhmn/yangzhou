---
name: backend-testing
description: yangzhou 后端测试规矩(Kotlin/Spring + Testcontainers)。写/改任何后端测试、新建 Flyway 迁移、动 AbstractApiTest 或 cleanDatabase 表清单、跑 gradle test / integrationTest / build 验证前必读。触发:"写测试/加测试/单元测试/集成测试"、"测试红了/测试失败"、"新表/迁移/建表"、"AbstractApiTest"、"cleanDatabase"、"gradle 验证/跑测试"。
---

# Backend Testing

后端测试分两个世界,规矩不同;混用是本仓坏测试的第一来源。机制真相源是
`backend/api/src/integrationTest/kotlin/yangzhou/api/AbstractApiTest.kt`,本文是规矩的真相源;
AGENTS.md 只留指针与速记。AGENTS「过程」节的两条总则在此重申为地基:
非平凡行为变更必须带测试;声称完成前跑通相关验证。

## 双轨(2026-10-09 定;YPJ-100/spec 0078 起物理二分)

| | 单元测试 | 集成测试 |
|---|-----------|------------------|
| 位置 | `src/test/kotlin/...` | `src/integrationTest/kotlin/...`(物理二分,目录即边界;越界引用编译期报错) |
| CI 跑? | **是**——`gradle test`(免 Docker) | 否——merge 前本人本地 `gradle integrationTest`;nightly workflow 全量兜底,红了必修 |
| Spring? | 永不起 | 全量 `@SpringBootTest`(经 AbstractApiTest) |
| 数据库? | 不碰 | Testcontainers 真 Postgres |
| 并行 | 类级并行安全 | 顺序 |

## 单元测试(src/test)

目标:快、密闭,验证业务分支/校验/状态迁移/错误映射/时间行为。类级并行安全的
硬条件:无共享可变全局态、不碰真 DB/文件/网络、不起 Spring。

- 引擎逻辑在 `backend/domain`(纯 Kotlin)直测——引擎是纯函数,无需 mock 容器。
- 断言用 kotlin.test(`assertEquals` / `assertFailsWith` 等,仓内既有惯例)。

## 集成测试(api 模块,extends AbstractApiTest)

目标:service+repository+Flyway+PostgreSQL+HTTP 全链路黑盒。**必须 extends
AbstractApiTest**,基类给的不要自造:

- Testcontainers 真 Postgres(容器管理全在基类)。
- `@BeforeEach cleanDatabase()`——固定表清单按 FK 依赖序 delete,每测试起点全净。
- `bootstrapAndAuth()`(引导工作区并返回已认证 RestTestClient)、
  `createProject(authed, key)`、`createItem(authed, key, title)` 等 helper。

规则:

- **唯一 seam = REST API 黑盒**:数据准备走 API(基类 helper 或直接调端点),
  断言响应体**和**库内状态(必要时 `jdbc` 直查,基类已注入)。
- 每测试自清自建、只造自己要的数据——不依赖别的用例残留,必须可单跑。
- 禁 `@Transactional` 回滚、MockMvc、@WebMvcTest、TestRestTemplate(spec 0014 定)。
- 敏感值(密码/token)永不进日志;登录失败消息保持笼统。

## 新表 → cleanDatabase 清单(建迁移的连带动词)

`cleanDatabase()` 的表清单是用例间隔离的机制。**新增 Flyway 迁移建表时,必须把
新表同步加进清单**(按 FK 依赖序,子表在父表前)。忘加 = 残渣跨用例泄漏,
flake 温床——本仓 e2e 的 globalSetup 清库就是历史教训的产物,后端别再欠一笔。

## 单测优先(2026-10-18 用户定)

**多写单元测试,integration test 只留给关键路径**(成功主链 + 重要错误映射/契约边界)。
纯 service 分支、校验、解析、状态迁移默认落单元(Fake gateway 注入即可);
不关键路径不再为"全链路安全感"写集成。V70 后单测并行,写单测很便宜。

## 什么改什么测(需求矩阵)

- service 业务分支/校验 → 单元测试
- controller/端点 → 集成测试(成功 + 重要错误路径)
- repository → **仅**自定义 SQL/原子迁移/约束/锁才写集成;普通 CRUD 派生查询不写
- 引擎纯函数 → domain 模块直测
- 平凡改动(文案/样式/注释)不需要测试

## Clock 注入(时间敏感代码)

凡代码读时间(截止/超时/过期/审计),构造注入 `java.time.Clock`,测试用
`Clock.fixed(...)` 钉死时刻,断言才确定。存量 `Instant.now()` 直散属历史债:
新代码按此约定,重构触碰即顺手迁移。

## 跑法(backend/ 目录下;`./gradlew` 同效)

```bash
gradle test                                              # CI 同款,纯单元
gradle integrationTest --tests "yangzhou.api.project.ProjectsApiTest"   # 单类,快反馈
gradle integrationTest                                   # 全量,merge 前本人必跑
gradle build --no-daemon                                 # 只含单元;DoD = build + integrationTest 双绿(YPJ-100 起 check 不再拖集成)
```

集成测试起不来,先查 Docker(Testcontainers 依赖它),再查容器残留
(`docker ps -a | grep testcontainers`)。
