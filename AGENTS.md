# yangzhou — 仓库指南(给 agent)

开源项目管理系统。核心:**匹配引擎**(Requirement × Capability → 单人输出可行性/技能差距,团队输出分配建议)。API-first:CLI 与 Web 都是 OpenAPI 契约的瘦客户端。栈:Kotlin + Spring Boot 4 / Postgres + JSONB / Next.js + MUI + TS / AGPL-3.0。

## 真相源(动笔前按触发条件读)

- **命名实体或写用户可见文案前** → 词汇表 `D:\obsidian\projects\yangzhou\CONTEXT.md`(**Item** 不是 Task;Workspace/Project/Team/Member/Attribute/**Requirement**/**Capability**)
- **实现引擎、实体关系或输出形态前** → `D:\obsidian\projects\yangzhou\DOMAIN.md`(判定 5 形态、绿黄红聚合规则、领域规则 6 条)
- **实现某张票时** → YPJ item(票自带验收清单;版本 spec 对应 `docs/spec/00NN`;历史 Linear 票号 JCW-* 仅作 spec 内引用锚点,Linear 已冻结只读)
- **做 ItemGroup/视图/导航类功能前** → `docs/requirement/ItemGroup PRD.md`(§28.2 Phase 划分;原 requirement.md 随笔已迁 Obsidian Thoughts.md)
- **动 schema 或架构前** → `docs/adr/`(0001 三层容器 / 0002 Item 同质树 / 0003 统一属性 / 0004 monorepo 与工具链)

## 布局(ADR-0004)

```
backend/   Gradle 多模块:domain(纯 Kotlin,零 Spring)· api(REST 薄层)· persistence(Postgres)· cli(fat-jar)
web/       Next.js + MUI + TS,瘦客户端
docs/      adr/ · spec/ · architecture/(puml 类图) · requirement/(PRD) · planning/
```

## 领域硬规则

- `domain` 零 Spring/DB 依赖——引擎是纯函数,从 prototype 抬来(分支 `prototype/matching-engine-output`)。注意:这是独立 Gradle 模块,与 chess 单体应用"禁止 domain 包"的约定不同,属有意为之(ADR-0002/0003 的物理形态)。
- 角色永不进匹配输入(ADR-0003);等级刻度 1–4;presence 需求为默认,min-level 只用于关键路径。
- 用户可见文案中文优先,文案外置。

## 类图

- 领域类图 = PlantUML 源码 `docs/architecture/domain.puml`(引擎模型)。**改 `backend/domain` 的实体或引擎签名后必须同步更新它**。
- 实体概念图 = `docs/architecture/entities.puml`(persistence/schema,只画领域字段,parentId 反转为父侧 children)。**改 Flyway schema 后必须同步更新它**。
- 两图真相源都是代码/表结构,图是缓存;不同步即过期。**顶部一律加 `hide empty members`**。不提交生成图片;用 VSCode PlantUML 插件或 plantuml.com 渲染预览。

## 过程(chess 实战约定平移)

- 票即计划:实现前读 YPJ 票 + spec 对应故事,不凭记忆。
- 切票边界自查(结论 ≤2 行):本票收尾干净?下一步依赖本会话推理?handoff 会不会复述 AGENTS/Linear 已有内容?→ clear / compact / handoff 三选一,自包含默认 clear。
- sprint 收口(全部票 Done)时,主动提醒用户做 handoff(由用户执行)。
- 外科手术式改动,不顺手重构无关代码。
- 最简可行实现,不加投机抽象。
- 非平凡行为变更必须带测试。
- 声称完成前跑通相关验证;后端以 `./gradlew build --no-daemon` 绿 + bootRun 能启动为准(临时端口验证,起后即停)。
- 大输出/多命令优先 context-mode 批处理工具,Bash 仅琐碎命令。
- `api` 模块内部按垂直切片组织(project / item / attribute / …):实体+DTO+service+controller 同包;技术配置归 `config.*`。

## Issue 工作流(PR 模式;票在 YPJ 项目,2026-10-06 起 Linear 冻结只读)

1. 开工:`git checkout main && git pull --ff-only && git checkout -b cwjiang/YPJ-{N}-{short}`;同时 `yz assign YPJ-{N} me && yz items move YPJ-{N} "In Progress"`(开工须有主,未 assign 会被 move 409 拒)
2. 实现带测试,跑全相关验证
3. PR:`gh pr create --base main --title "YPJ-{N}: ..."`;开出后 `yz items move YPJ-{N} "In Review"`
4. **停**,等用户审阅合并
5. 合并后同回合完成:`git checkout main && git pull --ff-only` → 删分支 → **YPJ 移 Done**(`yz items move YPJ-{N} "Done"`,非可选)→ 扫父 item:子票全 Done 则父票 Done,否则 In Progress。PR 已合而 item 停在 In Progress = 流程破损态。

## Kotlin 风格

- 尽量 `val`;实体/DTO 用不可变 data class
- 更新用 `copy(...)` + save,用返回实例
- 小切片的 DTO/请求/响应放同一文件,吵了再拆

## 命名分层

- **domain**(引擎模型)与 **persistence**(DB 行)共用裸名(Item/Member/Capability/Requirement/…),包即命名空间;同文件两用时用 import 别名(`import yangzhou.domain.Item as DomainItem`)
- API 线型(wire)一律语义后缀(`XxxRequest` / `XxxResponse`),不复用裸名——裸名永无三义

## 持久层(ADR-0004;chess 实战约定平移)

- Spring Data JDBC + Flyway;禁 JPA/Hibernate/Exposed/BaseEntity 继承
- 实体主构造直接声明 `id` / `objectId` / `createdAt` / `updatedAt`;`objectId` 在 Kotlin 生成
- 对外只暴露公共 ID(`projectId` / `itemId`),永不暴露内部数字 id;FK 用 `object_id` UUID 列
- migration 用 SQL identity + 命名约束;枚举 = Kotlin enum + VARCHAR + CHECK 约束
- 本机 Postgres 由共享 compose 提供(`F:/code/docker-compose/postgresql`),不建项目本地 compose;测试库 `yangzhou_test`

## 测试

- 唯一 seam = REST API 黑盒(spec);引擎纯函数直测。
- 单元测试:类级并行安全——无共享可变全局态、不碰真 DB/网络,不起 Spring;CI 只跑单元(`gradle test`)。
- 集成测试:`@SpringBootTest(RANDOM_PORT)` + `RestTestClient`;不用 MockMvc / @WebMvcTest / TestRestTemplate;不用 @Transactional 回滚——每测试自清自建、只造自己要的数据,断言响应体与库内状态。**必须 extends AbstractApiTest**(靠基类 @Tag 继承归轨,spec 0014)。
- 双轨(2026-10-09):CI 只跑单元;集成测试 merge 前本人本地 `gradle integrationTest`;nightly workflow 全量兜底,红了必修。
- 敏感值(密码/token)永不进日志;登录失败消息保持笼统。
- **不要猜前端或后端是否已运行,需要运行环境的,告知需要的运行环境,命令,从我这里确认**。

### E2E(web/;Playwright,并行按用例隔离)

- 后端跑 **test profile**(库 yangzhou_test):`gradle :api:bootRun --args='--spring.profiles.active=test'`;前端由 webServer 拉起 prod server(`next build && next start`,独立构建目录 .next-e2e,不踩日常 dev 的 .next);凭证与地址在 `web/e2e/.env`(模板 `.env.example`),不硬编码不提交。
- 标准全量跑法(web/ 下):`E2E_FRONT_PORT=3001 E2E_BASE_URL=http://localhost:3001 E2E_API_URL=http://localhost:8080 BACKEND_URL=http://localhost:8080 npx playwright test`;globalSetup 自带**清测试库**(残渣 flake 解法;Fast Refresh 类由 prod server 根除,spec 0021 As-built),勿绕过;BACKEND_URL 必须 8080(rewrites 代理目标,8081 会污染 PM 库);手动清库 `npm run db:reset`
- CI e2e.yml 只跑 **@smoke 冒烟**(`npx playwright test --grep "@smoke"`;grep 不作用于依赖项目,auth 3 例全跑提供登录前提,实跑 ~12 例);完整套件 = merge 前本地全量,跑法不变(spec 0015)
- 用例按模块分文件(一个功能域一个 `*.spec.ts`),`fullyParallel`;每用例经 fixture 独享唯一 key 项目(API 预置),**不依赖其它用例留下的状态,可单跑**。
- 用例内只对被测行为走 UI;数据准备走 API helpers(`e2e/support/helpers.ts`)。MUI 对话框淡出期(~200ms)会吞下层点击,断言/点下层前先 `expect(dialog).toBeHidden()`。

## 错误与契约

- service fail-fast 抛业务异常;HTTP 映射集中 GlobalExceptionHandler;统一错误形状(code/message/path/可选 fields);请求校验用 Bean Validation
- OpenAPI + Scalar(不用 Swagger UI/Knife4j);注解最少(controller @Tag + 偶尔摘要),字段级只在生成文档误导时补
- 改 REST API(新增/改参数/删端点)必须同步手测脚本 `backend/script/api.http`:新端点按序号入对应区块,约束类行为补进末尾「负例区」,变量靠响应 handler 回填
- Controller 统一格式:类级 `@RequestMapping("/api")` 只扛 API 前缀(未来 /api/v1 一次替换),资源段(/projects、/items…)全在方法级
- 日志:默认 SLF4J/Logback,YAML 配级别;不加 MDC/关联 ID/自定义 logback 除非新决策
- 不加 mapstruct;slice 内手写小 mapper

## 构建与运行

- JDK 25(LTS)。后端在 `backend/`:`gradle build`(含测试)/ `gradle :domain:run`(引擎 Demo,输出与 prototype S1–S7 可比对)。
- `./gradlew` 同效;首次需下载发行包,国内网络慢属已知,用本地 gradle 即可。
- CI(GitHub Actions):`backend.yml` 跑 `gradle test`(纯单元,免 Docker;spec 0014);`nightly.yml` 每日全量兜底(含集成,红了必修);`e2e.yml` web/backend 变更起 bootRun + Playwright 整跑 E2E
- 本机开发库:`yangzhou`(共享 compose,已建);bootRun 用 `gradle :api:bootRun`。
- **双 profile 并存**(2026-10-06 起):8080 常跑 **test profile**(库 `yangzhou_test`,E2E 用);**PM 日常实例 = default profile**(库 `yangzhou`,dogfood 项目 YPJ),约定端口 **8081**:`gradle :api:bootRun --args='--server.port=8081'`。CLI `yz` 的 session(`~/.yangzhou/session.json`)指向谁就写谁的库——动 PM 数据前确认 server 指向 default 实例。
- PM 实例部署形态(已拍板):本机常驻即可;Postgres 备份手工期(`pg_dump`),自动化另票。
- **⚠ 前端命令必须在 `web/` 目录下执行**:`cd /d/code/yangzhou/web && npx next build` / `npm run dev` / `npx playwright test` 等。agent 的 shell CWD 每次重置到 repo 根,漏 cd 会在错误目录跑命令导致 `.next` 污染或路径找不到。

## 进程管理(本机实操)

- **严禁 `taskkill /IM node.exe`**——pi 本体就是 node 进程,按进程名杀会自杀;也慎杀全部 java.exe(gradle daemon 可杀,但用完再起更省)。停开发服务一律**按端口找 PID**:`netstat -ano | findstr :3000` → `taskkill /PID <pid> /F`。
- context-mode 批处理工具实际跑 PowerShell——`&&`、`find`、`/dev/null` 语法会挂;简单命令直接用 Bash。
- headroom 压缩开启时:被压缩的读取输出不可当编辑锚点(原文会被搅坏);大文件读改一律 python 原地处理。
- CLI/脚本中文输出在 PowerShell 显示乱码(UTF-8 被按 GBK 解码):先 `chcp 65001` 或改用 Git Bash。

## 工作流

main 干线开发;原型留 `prototype/*` 分支;每张票 = 一个 YPJ item(dogfood),验收清单全绿才关票。

- **spec 文件是版本必须项**:开工前立 `docs/spec/00NN`(增量节,概要设计),完工后补 As-built;无 spec 不开工(2026-10-08 定,V16 系补录见 0013)。Linear 已冻结只读(历史存档),不再开新票。
- **每票实现前三件套**(2026-10-09 立):spec 增量节含 ①需求(写细,含范围/非目标)②详细设计(代码级修改计划:改哪些文件、怎么改、否决的备选)③手工测试用例(供用户 build/debug 阶段执行);用户过目后才编码。
- **编码完成后停**:功能代码+测试代码写完即停,等用户 build/debug,通过后再 PR。
